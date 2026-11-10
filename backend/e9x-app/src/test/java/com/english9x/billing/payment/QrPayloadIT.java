package com.english9x.billing.payment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.domain.billing.VietQr;
import com.english9x.support.TestAuth;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class QrPayloadIT extends PaymentIT {

    private ResponseEntity<byte[]> png(String code, UUID asUser) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(TestAuth.accessToken(asUser));
        return http.exchange("/api/orders/" + code + "/qr.png", HttpMethod.GET, new HttpEntity<>(null, h), byte[].class);
    }

    private static String decode(byte[] pngBytes) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))), hints).getText();
    }

    @Test
    void decodesToConfiguredAccountAmountAndNote() throws Exception {
        account(BIN, ACCOUNT_NO);
        UUID u = user("qr");
        UUID plan = plan(499_000, 3, 2, true);
        Res r = placeOrder(u, plan, course("PUBLISHED"), course("PUBLISHED"));
        assertEquals(201, r.status(), r.raw());
        String code = r.body().path("orderCode").asText();
        String note = r.body().path("transferNote").asText();
        assertEquals("/api/orders/" + code + "/qr.png", r.body().path("qrPngUrl").asText());

        ResponseEntity<byte[]> res = png(code, u);
        assertEquals(200, res.getStatusCode().value());
        assertEquals(MediaType.IMAGE_PNG, res.getHeaders().getContentType());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(res.getBody()));
        assertEquals(512, image.getWidth());
        assertEquals(512, image.getHeight());

        String payload = decode(res.getBody());
        assertEquals(r.body().path("qrPayload").asText(), payload, "the image carries exactly the payload the order answered with");
        Map<String, String> top = VietQr.parseTopLevel(payload);
        assertEquals("499000", top.get("54"));
        assertEquals("704", top.get("53"));
        Map<String, String> merchant = VietQr.parseTopLevel(top.get("38"));
        Map<String, String> beneficiary = VietQr.parseTopLevel(merchant.get("01"));
        assertEquals(BIN, beneficiary.get("00"));
        assertEquals(ACCOUNT_NO, beneficiary.get("01"));
        assertEquals(note, VietQr.parseTopLevel(top.get("62")).get("08"));
        assertTrue(note.startsWith("E9X " + code));
        // the checksum field is the last one: a payload whose CRC is wrong would be rejected by every bank app
        assertEquals(4, top.get("63").length());
    }

    @Test
    void imageIsOnlyForTheOwnerAndOnlyWhileTheAccountExists() {
        account(BIN, ACCOUNT_NO);
        UUID u = user("qrown");
        String code = placeOrder(u, plan(100_000, 1, 1, true), course("PUBLISHED")).body().path("orderCode").asText();
        assertEquals(404, png(code, user("qrother")).getStatusCode().value(), "nobody reads another person's QR");
        assertEquals(404, png("ZZZZZZ", u).getStatusCode().value());
        su.update("update payment_accounts set active = false");
        Res noAccount = placeOrder(u, plan(100_000, 1, 1, true), course("PUBLISHED"));
        assertEquals(201, noAccount.status(), "an order can be placed before an account is configured");
        assertTrue(noAccount.body().path("qrPayload").isNull());
        assertTrue(noAccount.body().path("qrPngUrl").isNull());
        assertEquals(404, png(noAccount.body().path("orderCode").asText(), u).getStatusCode().value());
    }

    @Test
    void longCourseListFallsBackToOrderCode() throws Exception {
        account(BIN, ACCOUNT_NO);
        UUID u = user("qrlong");
        UUID plan = plan(900_000, 6, 10, true);
        List<UUID> courses = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            courses.add(course("PUBLISHED"));
        }
        Res r = placeOrder(u, plan, courses.toArray(UUID[]::new));
        assertEquals(201, r.status(), r.raw());
        String code = r.body().path("orderCode").asText();
        assertEquals("E9X " + code, r.body().path("transferNote").asText(), "nine course codes do not fit e9x.billing.transfer-note-max-length");
        Map<String, String> top = VietQr.parseTopLevel(decode(png(code, u).getBody()));
        assertEquals("E9X " + code, VietQr.parseTopLevel(top.get("62")).get("08"));
        assertNotNull(r.body().path("qrPayload").asText());
    }

    @Test
    void imageIsStableForTheSameOrder() {
        account(BIN, ACCOUNT_NO);
        UUID u = user("qrstable");
        String code = placeOrder(u, plan(100_000, 1, 1, true), course("PUBLISHED")).body().path("orderCode").asText();
        assertArrayEquals(png(code, u).getBody(), png(code, u).getBody());
    }
}
