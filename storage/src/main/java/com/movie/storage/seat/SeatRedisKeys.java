package com.movie.storage.seat;

public final class SeatRedisKeys {

    private SeatRedisKeys() {
    }

    private static String tag(String screeningId) {
        return "{" + screeningId + "}";
    }

    public static String reserved(String screeningId) {
        return "seat:" + tag(screeningId) + ":reserved";
    }

    public static String hold(String screeningId, String seatNo) {
        return "seat:" + tag(screeningId) + ":hold:" + seatNo;
    }

    public static String holdData(String screeningId, String holdId) {
        return "hold:" + tag(screeningId) + ":" + holdId;
    }

    public static String tokenHold(String screeningId, String token) {
        return "token:" + tag(screeningId) + ":" + token + ":hold";
    }

    public static String tokenLastHold(String screeningId, String token) {
        return "token:" + tag(screeningId) + ":" + token + ":last_hold";
    }

    public static String tokenReservation(String screeningId, String token) {
        return "token:" + tag(screeningId) + ":" + token + ":reservation";
    }

    public static String tokenCancelled(String screeningId, String token) {
        return "token:" + tag(screeningId) + ":" + token + ":cancelled";
    }

    public static String payment(String screeningId, String paymentId) {
        return "payment:" + tag(screeningId) + ":" + paymentId;
    }

    public static String paymentHold(String screeningId, String paymentId) {
        return payment(screeningId, paymentId) + ":hold";
    }

    public static String paymentScreening(String paymentId) {
        return "payment:screening:" + paymentId;
    }

    public static String channel(String screeningId) {
        return "reservations:" + tag(screeningId);
    }

    public static String stream(String screeningId) {
        return "stream:" + tag(screeningId) + ":reservations";
    }

    public static String deadLetterStream(String screeningId) {
        return stream(screeningId) + ":dlq";
    }
}
