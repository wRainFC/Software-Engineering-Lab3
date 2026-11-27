CREATE TABLE users (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, account VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL, role VARCHAR(16) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE address (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, customer_id BIGINT NOT NULL,
  contact VARCHAR(80) NOT NULL, phone CHAR(11) NOT NULL, region VARCHAR(100) NOT NULL,
  detail VARCHAR(240) NOT NULL,
  FOREIGN KEY (customer_id) REFERENCES users(id)
);
CREATE TABLE shop (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, owner_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL, phone CHAR(11) NOT NULL, areas VARCHAR(500) NOT NULL,
  fee INT NOT NULL CHECK(fee >= 0), status VARCHAR(16) NOT NULL, reason VARCHAR(240) NOT NULL DEFAULT '',
  version BIGINT NOT NULL DEFAULT 0,
  FOREIGN KEY (owner_id) REFERENCES users(id)
);
CREATE TABLE dish (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, shop_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL, category VARCHAR(80) NOT NULL, price INT NOT NULL CHECK(price BETWEEN 1 AND 99999),
  stock INT NOT NULL CHECK(stock BETWEEN 0 AND 9999), held INT NOT NULL DEFAULT 0,
  CHECK(held >= 0 AND stock + held <= 9999), shelf BOOLEAN NOT NULL,
  version BIGINT NOT NULL DEFAULT 0, INDEX(shop_id, category, price),
  FOREIGN KEY (shop_id) REFERENCES shop(id)
);
CREATE TABLE cart_item (
  customer_id BIGINT NOT NULL, dish_id BIGINT NOT NULL,
  quantity INT NOT NULL CHECK(quantity BETWEEN 1 AND 99), PRIMARY KEY(customer_id,dish_id),
  FOREIGN KEY (customer_id) REFERENCES users(id),
  FOREIGN KEY (dish_id) REFERENCES dish(id)
);
CREATE TABLE order_request (
  customer_id BIGINT NOT NULL, request_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  digest CHAR(64) NOT NULL, lease_until BIGINT NOT NULL, owner_token CHAR(36) NOT NULL,
  PRIMARY KEY(customer_id,request_no),
  FOREIGN KEY (customer_id) REFERENCES users(id)
);
CREATE TABLE orders (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, order_no CHAR(36) NOT NULL UNIQUE,
  customer_id BIGINT NOT NULL, shop_id BIGINT NOT NULL,
  request_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, status VARCHAR(20) NOT NULL,
  items_total BIGINT NOT NULL, fee INT NOT NULL, total BIGINT NOT NULL,
  contact VARCHAR(80) NOT NULL, phone CHAR(11) NOT NULL, address VARCHAR(360) NOT NULL,
  consumed BOOLEAN NOT NULL DEFAULT FALSE, created_at BIGINT NOT NULL, deadline BIGINT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0, reason VARCHAR(240) NOT NULL DEFAULT '',
  UNIQUE(customer_id,request_no), FOREIGN KEY(customer_id,request_no) REFERENCES order_request(customer_id,request_no),
  INDEX(status,deadline), INDEX(shop_id,created_at), INDEX(customer_id,created_at), INDEX(created_at),
  FOREIGN KEY (customer_id) REFERENCES users(id),
  FOREIGN KEY (shop_id) REFERENCES shop(id)
);
CREATE TABLE order_item (
  order_id BIGINT NOT NULL, dish_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL, price INT NOT NULL, quantity INT NOT NULL, PRIMARY KEY(order_id,dish_id),
  FOREIGN KEY (order_id) REFERENCES orders(id),
  FOREIGN KEY (dish_id) REFERENCES dish(id)
);
CREATE TABLE payment (
  order_id BIGINT PRIMARY KEY, pay_no CHAR(36) NOT NULL UNIQUE,
  amount BIGINT NOT NULL, paid_at BIGINT NOT NULL,
  FOREIGN KEY (order_id) REFERENCES orders(id)
);
CREATE TABLE refund (
  order_id BIGINT PRIMARY KEY, refund_no CHAR(36) NOT NULL UNIQUE,
  amount BIGINT NOT NULL, status VARCHAR(20) NOT NULL, reason VARCHAR(240) NOT NULL DEFAULT '',
  updated_at BIGINT NOT NULL, attempts INT NOT NULL DEFAULT 1, INDEX(status,updated_at),
  FOREIGN KEY (order_id) REFERENCES orders(id)
);
CREATE TABLE cancel_request (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL, reason VARCHAR(240) NOT NULL, reply VARCHAR(240) NOT NULL DEFAULT '',
  handled_by BIGINT, created_at BIGINT NOT NULL,
  pending_order BIGINT GENERATED ALWAYS AS (CASE WHEN status='PENDING' THEN order_id END) STORED UNIQUE,
  FOREIGN KEY (order_id) REFERENCES orders(id),
  FOREIGN KEY (handled_by) REFERENCES users(id)
);
CREATE TABLE delivery_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL,
  rider_id BIGINT NOT NULL, status VARCHAR(20) NOT NULL,
  reason VARCHAR(240) NOT NULL DEFAULT '', assigned_at BIGINT NOT NULL, picked_at BIGINT, ended_at BIGINT,
  active_order BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('ASSIGNED','PICKED') THEN order_id END) STORED UNIQUE,
  INDEX(rider_id,assigned_at),
  FOREIGN KEY (order_id) REFERENCES orders(id),
  FOREIGN KEY (rider_id) REFERENCES users(id)
);
CREATE TABLE operation_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, operator_id BIGINT, role VARCHAR(16) NOT NULL,
  at_time BIGINT NOT NULL, target VARCHAR(160) NOT NULL, order_id BIGINT, shop_id BIGINT,
  action VARCHAR(80) NOT NULL, before_state VARCHAR(500) NOT NULL, after_state VARCHAR(500) NOT NULL,
  result VARCHAR(16) NOT NULL, reason VARCHAR(240) NOT NULL, request_no VARCHAR(64) NOT NULL,
  INDEX(order_id,at_time), INDEX(shop_id,at_time), INDEX(operator_id,at_time), INDEX(at_time)
);
CREATE TABLE action_receipt (
  operator_id BIGINT NOT NULL, request_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_id BIGINT NOT NULL, digest CHAR(64) NOT NULL,
  result_status VARCHAR(20) NOT NULL, result_version BIGINT NOT NULL, PRIMARY KEY(operator_id,request_no),
  FOREIGN KEY (operator_id) REFERENCES users(id),
  FOREIGN KEY (order_id) REFERENCES orders(id)
);

CREATE TABLE bootstrap_marker (name VARCHAR(80) PRIMARY KEY);
