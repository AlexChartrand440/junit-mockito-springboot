package es.sm2baleares.tinglao.service.impl;

import es.sm2baleares.tinglao.exception.OrderException;
import es.sm2baleares.tinglao.service.AmazonDeliveryService;
import es.sm2baleares.tinglao.service.DeliveryScoreService;
import es.sm2baleares.tinglao.service.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import es.sm2baleares.tinglao.model.Discount;
import es.sm2baleares.tinglao.model.Order;

import java.util.Calendar;
import java.util.Date;

/**
 * Created by pablo.beltran on 21/09/2016.
 */
@Service
@Qualifier("AmazonDeliveryService")
public class AmazonDeliveryServiceImpl implements AmazonDeliveryService {


	@Autowired
	DeliveryScoreService deliveryScoreService;

	/**
	 * {@inheritDoc}
	 */
	public Order newOrder(String description, double basePrice, boolean premiumCustomer) {
		Order order = new Order();
		order.setDescription(description);
		order.setBasePrice(basePrice);
		order.setDelivered(false);
		order.setSent(false);
		order.setPremium(premiumCustomer);
		calcFinalPrice(order);

		return order;
	}


	/**
	 * {@inheritDoc}
	 */
	public void addDiscount(Order order, Discount discount) {
		if (discount != null) {
			order.getDiscounts().add(discount);
		}
		calcFinalPrice(order);
	}

	/**
	 * {@inheritDoc}
	 */
	public void markSent(Order order, Date sendDate) throws OrderException {
		if (order.isSent()) {
			throw new OrderException("El pedido ya está enviado");
		}

		order.setSendDate(sendDate);
		Date estimatedDelivery = calcEstimatedDeliveryDate(order.isPremium());

		order.setEstimatedDelivery(estimatedDelivery);
		order.setSent(true);
	}

	/**
	 * {@inheritDoc}
	 */
	/*
	 * El método markDelivered no sería testeable si se quiere evitar el envío de emails.
	 * Posibles soluciones a acoplamiento EmailService:
	 *
	 * 1 - Rediseñar EmailService de modo que sea un servicio stateless, ahora no lo es. (y si viene en un jar que no
	 * 		podemos tocar?)
	 * 2 - Implementar un EmailServiceFactory, introducirlo como dependencia aquí. (es lo más elegante, aunque no
	 * 		siempre será posible)
	 * 3 - Incluir método "protected" o sin qualifier en la clase que provea la dependencia y hacer spy de la clase en
	 * 		el test. Stubear ese método para que devuelva un mock del servicio.
	 *
	 */
	public void markDelivered(Order order, Date deliverDate) throws OrderException {
		if (!order.isSent()) {
			throw new OrderException("El pedido no está enviado");
		}

		if (order.isDelivered()) {
			throw new OrderException("El pedido ya está entregado");
		}

		order.setDelivered(true);
		order.setRealDelivery(deliverDate);

		long deliveryScore = calculateDeliveryDateScore(order);

		//Submit score
		deliveryScoreService.submitDeliveryPoints(deliveryScore);

		//Send email notification
		EmailService emailService = getEmailServiceInstance(order);

		emailService.sendDeliveryNotification();
	}

	//Fix para habilitar mocking de EmailService cuando no sea posible crear Factory o rediseñar el servicio.
	EmailService getEmailServiceInstance(Order order) {
		return new EmailService(order);
	}

	/**
	 * Dado un pedido entregado, calcula la puntuación obtenida por la diferencia entre fecha estimada y de entrega.
	 * @param order
	 * @return
	 */
	private long calculateDeliveryDateScore(Order order) {
		final long diff = order.getEstimatedDelivery().getTime() - order.getRealDelivery().getTime();
		return diff / (60 * 60 * 1000);
	}

	/**
	 * Calcula el precio final del producto, teniendo en cuenta los descuentos.
	 * @param order
	 */
	private void calcFinalPrice(Order order) {
		double totalDiscount = 0;

		for (Discount discount : order.getDiscounts()) {
			totalDiscount += discount.getPercent();
		}

		//calc discount
		double finalPrice = order.getBasePrice() - (order.getBasePrice() * totalDiscount / 100.0);

		//round 2 decimals chapucer
		finalPrice *= 100;
		finalPrice = Math.round(finalPrice);
		finalPrice /= 100;

		//set final price
		order.setFinalPrice(finalPrice);
	}

	/**
	 * Calcula fecha estimada de entrega.
	 * @param premiumCustomer
	 * @return
	 */
	private Date calcEstimatedDeliveryDate(boolean premiumCustomer) {
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(new Date());

		if (premiumCustomer) {
			calendar.add(Calendar.DAY_OF_MONTH, ESTIMATED_DAYS_TO_DELIVER_PREMIUM);
		} else {
			calendar.add(Calendar.DAY_OF_MONTH, ESTIMATED_DAYS_TO_DELIVER_REGULAR);
		}
		return calendar.getTime();
	}
}
