package com.ecommerce.order_service.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecommerce.order_service.client.dto.CartItem;
import com.ecommerce.order_service.client.dto.CartResponse;
import com.ecommerce.order_service.client.dto.ProductRequest;
import com.ecommerce.order_service.client.dto.ProductResponse;
import com.ecommerce.order_service.client.dto.Role;
import com.ecommerce.order_service.client.dto.UserResponse;
import com.ecommerce.order_service.config.RabbitMQConfig;
import com.ecommerce.order_service.entity.Notification;
import com.ecommerce.order_service.entity.Order;
import com.ecommerce.order_service.entity.OrderItem;
import com.ecommerce.order_service.entity.dto.OrderItemResponse;
import com.ecommerce.order_service.entity.dto.OrderResponse;
import com.ecommerce.order_service.entity.dto.PaymentResponse;
import com.ecommerce.order_service.exception.ClientDownException;
import com.ecommerce.order_service.exception.EmptyCartException;
import com.ecommerce.order_service.exception.InvalidRequestException;
import com.ecommerce.order_service.exception.OrderNotFoundException;
import com.ecommerce.order_service.repository.OrderRepository;
import com.ecommerce.order_service.security.ValidateRequest;
import com.ecommerce.order_service.service.rabbitMQ.OrderPublisher;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;

@Service
public class OrderServiceImpl implements OrderService{

	private final OrderRepository orderRepo;
	private final ProductService productService;
	private final CustomerService customerService;
	private final CartService cartService;
	private final OrderPublisher orderPublisher;
	private final ValidateRequest validate;
	
	public OrderServiceImpl(OrderRepository orderRepo,ProductService productService,
			CustomerService customerService,CartService cartService,
			OrderPublisher orderPublisher,ValidateRequest validate) {
		this.orderRepo = orderRepo;
		this.productService = productService;
		this.cartService = cartService;
		this.customerService = customerService;
		this.orderPublisher = orderPublisher;
		this.validate = validate;
	}
	
	@Transactional
	public String placeOrder(int customerId) {
		
		if(!validate.validateCustomer(customerId)) {
			throw new InvalidRequestException("Invalid token");
		}
		ResponseEntity<CartResponse> cart = cartService.fetchAllItems(customerId);
		if(cart.getBody().getCustomerId()==0) {
			throw new ClientDownException("Cart service is down, please try after sometime!");
		}
		if(cart.getBody().getItems().isEmpty()) {
			throw new EmptyCartException("Cart is empty, please add items in your cart");
		}
		
		//ResponseEntity<UserResponse> user = customerClient.getUser(customerId);
		
		
		double totalAmount = cart.getBody().getItems().stream()
			    .mapToDouble(item -> item.getPrice() * item.getQuantity())
			    .sum();
		
		Order order = new Order();
		order.setCustomerId(customerId); 
		order.setOrderDate(LocalDateTime.now());
		order.setStatus("PENDING");
		order.setTotalPrice(BigDecimal.valueOf(totalAmount));
		
		List<OrderItem> orderItems = new ArrayList<>();
		for(CartItem item : cart.getBody().getItems()) {
			//ResponseEntity<ProductResponse> product = productClient.getProductById(item.getProductId());
			OrderItem orderItem = new OrderItem();
			orderItem.setProductId(item.getProductId());
			orderItem.setQuantity(item.getQuantity());
			orderItem.setUnitPrice(BigDecimal.valueOf(item.getPrice()));
			orderItem.setOrder(order);
			orderItems.add(orderItem);
		}
		
		order.setOrderItems(orderItems);
		Order savedOrder = orderRepo.save(order);
		String clearResponse = cartService.clearCart(customerId).getBody();
		if(clearResponse.equals("Fallback")) {
			throw new ClientDownException("Cart service is down, please try after sometime");
		}
		//use RabbitMQ to share order details to payment service
		orderPublisher.sendOrderToPaymentQueue(savedOrder);
		
		return "Please complete the payment for Order ID "+savedOrder.getId();
	}
	
	@Transactional
	public OrderResponse trackOrder(int id) {
		Order order = orderRepo.findById(id).orElseThrow(()-> new OrderNotFoundException("Invalid order id : "+id));
		if(!validate.validateCustomer(order.getCustomerId())) {
			throw new InvalidRequestException("Invalid token");
		}
		//get customer details using customer client
		ResponseEntity<UserResponse> customer = customerService.fetchUser(order.getCustomerId());
		if(customer.getBody().getRole().equals(Role.SERVERDOWN)) {
			throw new ClientDownException("Customer Service is down, please try after sometime");
		}
		String customerName = customer.getBody().getUserName();
		OrderResponse response = new OrderResponse();
		
		response.setCustomerName(customerName);
		response.setOrderDate(order.getOrderDate());
		response.setTotalPrice(order.getTotalPrice());
		response.setOrderId(id);
		response.setStatus(order.getStatus());
		
		List<OrderItemResponse> itemResponse = new ArrayList<>();
		List<OrderItem> items = order.getOrderItems();
		
		for(OrderItem item : items) {
			ResponseEntity<ProductResponse> product = productService.fetchProductDetails(item.getProductId());
			if(product.getBody().getCategory().equals("Fallback")) {
				throw new ClientDownException("Product service is down, please try after sometime!");
			}
			OrderItemResponse orderItemResponse = new OrderItemResponse();
			orderItemResponse.setPrice(item.getUnitPrice());
			orderItemResponse.setProductName(product.getBody().getName());
			orderItemResponse.setQuantity(item.getQuantity());
			
			itemResponse.add(orderItemResponse);
		}
		
		response.setItems(itemResponse);
		return response;
	}
	
	@RabbitListener(queues = RabbitMQConfig.PAYMENT_QUEUE)
	@Transactional
	public void receiveOrderMessage(@Payload PaymentResponse paymentResponse, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
	    try {
	        int customerId = paymentResponse.getUserId();
	        int order_id = paymentResponse.getOrderId();

	        Order savedOrder = orderRepo.findById(order_id)
	                .orElseThrow(() -> new OrderNotFoundException("Invalid order no"));

	        if ("PAID".equals(paymentResponse.getStatus())) {

	            if ("SUCCESS".equals(savedOrder.getStatus())) return;

	            if ("PENDING".equals(savedOrder.getStatus()) && savedOrder.getCustomerId() == customerId) {
	                savedOrder.setStatus("PAID");
	                List<OrderItem> orderItems = savedOrder.getOrderItems();

	                for (OrderItem item : orderItems) {
	                    ResponseEntity<ProductResponse> product = productService.fetchProductDetails(item.getProductId());
	                    if(product.getBody().getCategory().equals("Fallback")) {
	                    	throw new ClientDownException("Product service is down, please try after sometime");
	                    }
	                    int quantity = product.getBody().getQuantity() - item.getQuantity();

	                    ProductRequest productRequest = new ProductRequest();
	                    productRequest.setQuantity(quantity);
	                    ProductResponse updateStockResponse= productService.updateStock(productRequest, item.getProductId()).getBody();
	                    if(updateStockResponse.getCategory().equals("Fallback")){
	                    	throw new ClientDownException("Product service is down, please try after sometime");
	                    }
	                }
	            } else {
	                savedOrder.setStatus("FAILED");
	            }

	            orderRepo.save(savedOrder);
	        }

	        ResponseEntity<UserResponse> customer = customerService.fetchUser(savedOrder.getCustomerId());
	        if(customer.getBody().getRole().equals(Role.SERVERDOWN)) {
				throw new ClientDownException("Customer Service is down, please try after sometime");
			}
	        
	        Notification notification = new Notification();
	        notification.setOrderId(savedOrder.getId());
	        notification.setStatus(savedOrder.getStatus());
	        notification.setAmount(savedOrder.getTotalPrice());
	        notification.setUserName(customer.getBody().getUserName());
	        notification.setUsermail(customer.getBody().getEmail());

	        orderPublisher.sendOrderToNotificationQueue(notification);
	        System.out.println("Order placed successfully!");

	        // Acknowledge the message manually after successful processing
	        channel.basicAck(tag, false);

	    } catch (Exception e) {
	        // Log the error and reject the message without requeueing
	        System.err.println("Error processing message: " + e.getMessage());
	        try {
	            channel.basicReject(tag, false); // false = do not requeue
	        } catch (IOException ioException) {
	            System.err.println("Error rejecting message: " + ioException.getMessage());
	        }
	    }
	}

	
	@Transactional
	public String cancelOrder(int orderId) {
		Order order = orderRepo.findById(orderId).orElseThrow(()-> new OrderNotFoundException("Invalid Order Number :"+orderId));
		
		order.setStatus("CANCELLED");
		
		Order canceledOrder = orderRepo.save(order);
		
		orderPublisher.sendOrderToPaymentCancelQueue(canceledOrder);
		
		ResponseEntity<UserResponse> customer = customerService.fetchUser(canceledOrder.getCustomerId());	
		if(customer.getBody().getRole().equals(Role.SERVERDOWN)) {
			throw new ClientDownException("Customer Service is down, please try after sometime");
		}
		
		Notification notification = new Notification();
		notification.setAmount(canceledOrder.getTotalPrice());
		notification.setOrderId(canceledOrder.getId());
		notification.setStatus(canceledOrder.getStatus());
		notification.setUsermail(customer.getBody().getEmail());
		notification.setUserName(customer.getBody().getUserName());
		
		orderPublisher.sendOrderToNotificationQueue(notification);
		
		
		return "Order canceled for the order id : "+canceledOrder.getId();
	}
}
