SELECT
  (SELECT COUNT(*) FROM orders o LEFT JOIN payment p ON p.order_id=o.id
   WHERE p.order_id IS NULL OR p.amount<>o.total OR o.total<>o.items_total+o.fee)
  + (SELECT COUNT(*) FROM orders o WHERE o.items_total <>
       COALESCE((SELECT SUM(i.price*i.quantity) FROM order_item i WHERE i.order_id=o.id),0)
       OR NOT EXISTS (SELECT 1 FROM order_item i WHERE i.order_id=o.id))
  + (SELECT COUNT(*) FROM orders o LEFT JOIN refund r ON r.order_id=o.id
       WHERE (o.status='CANCELLED')<>(r.order_id IS NOT NULL) OR r.amount<>o.total)
  + (SELECT COUNT(*) FROM delivery_task t JOIN orders o ON o.id=t.order_id
       WHERE t.active_order IS NOT NULL AND NOT ((t.status='ASSIGNED' AND o.status='READY')
         OR (t.status='PICKED' AND o.status='DELIVERING')))
  + (SELECT COUNT(*) FROM dish d WHERE d.stock<0 OR d.held<>COALESCE((
       SELECT SUM(i.quantity) FROM order_item i JOIN orders o ON o.id=i.order_id
       WHERE i.dish_id=d.id AND o.status IN ('WAITING','ACCEPTED')),0))
  + (SELECT COUNT(*) FROM payment p LEFT JOIN orders o ON o.id=p.order_id WHERE o.id IS NULL)
  + (SELECT COUNT(*) FROM refund r LEFT JOIN orders o ON o.id=r.order_id WHERE o.id IS NULL)
  + (SELECT COUNT(*) FROM delivery_task t LEFT JOIN orders o ON o.id=t.order_id WHERE o.id IS NULL)
  + (SELECT COUNT(*) FROM order_item i LEFT JOIN orders o ON o.id=i.order_id WHERE o.id IS NULL);
