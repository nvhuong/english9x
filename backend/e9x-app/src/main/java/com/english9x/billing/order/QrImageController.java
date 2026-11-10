package com.english9x.billing.order;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The QR image of one of the caller's orders that waits for payment. Anybody else's order, a settled order and an unconfigured account are all 404. */
@RestController
class QrImageController {

    private final OrderService orders;

    QrImageController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/api/orders/{code}/qr.png")
    ResponseEntity<byte[]> qr(@PathVariable String code) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).contentType(MediaType.IMAGE_PNG).body(orders.qrPng(code));
    }
}
