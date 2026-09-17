package com.igot.cb.util.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ErrorResponseTest {

    @Test
    void testBuilderAndGetters() {
        ErrorResponse error = ErrorResponse.builder()
                .code("ERR001")
                .message("Something went wrong")
                .httpStatusCode(500)
                .build();
        assertEquals("ERR001", error.getCode());
        assertEquals("Something went wrong", error.getMessage());
        assertEquals(500, error.getHttpStatusCode());
    }

    @Test
    void testEqualsSameObject() {
        ErrorResponse error = ErrorResponse.builder()
                .code("ERR001")
                .message("Error")
                .httpStatusCode(400)
                .build();
        assertEquals(error, error);
    }

    @Test
    void testEqualsDifferentObjectType() {
        ErrorResponse error = ErrorResponse.builder()
                .code("ERR001")
                .message("Error")
                .httpStatusCode(400)
                .build();
        assertNotEquals("some string", error);
    }

    @Test
    void testEqualsNull() {
        ErrorResponse error = ErrorResponse.builder()
                .code("ERR001")
                .message("Error")
                .httpStatusCode(400)
                .build();
        assertNotEquals(null, error);
    }

    @Test
    void testEqualsAndHashCodeWithSameValues() {
        ErrorResponse error1 = ErrorResponse.builder()
                .code("ERR001")
                .message("Error")
                .httpStatusCode(400)
                .build();
        ErrorResponse error2 = ErrorResponse.builder()
                .code("ERR001")
                .message("Error")
                .httpStatusCode(400)
                .build();
        assertEquals(error1, error2);
        assertEquals(error1.hashCode(), error2.hashCode());
    }

    @Test
    void testNotEqualsDifferentValues() {
        ErrorResponse error1 = ErrorResponse.builder()
                .code("ERR001")
                .message("Error")
                .httpStatusCode(400)
                .build();
        ErrorResponse error2 = ErrorResponse.builder()
                .code("ERR002")
                .message("Different")
                .httpStatusCode(404)
                .build();
        assertNotEquals(error1, error2);
    }

    @Test
    void testToStringContainsAllFields() {
        ErrorResponse error = ErrorResponse.builder()
                .code("ERR123")
                .message("Test Message")
                .httpStatusCode(404)
                .build();
        String str = error.toString();
        assertTrue(str.contains("ERR123"));
        assertTrue(str.contains("Test Message"));
        assertTrue(str.contains("404"));
    }

    @Test
    void testBuilderWithMissingFields() {
        ErrorResponse error = ErrorResponse.builder()
                .code("CODE_ONLY")
                .build();
        assertEquals("CODE_ONLY", error.getCode());
        assertNull(error.getMessage());
        assertEquals(0, error.getHttpStatusCode());
    }

    @Test
    void testEqualsWhenSomeFieldsAreNull() {
        ErrorResponse error1 = ErrorResponse.builder()
                .code(null)
                .message("Error")
                .httpStatusCode(400)
                .build();
        ErrorResponse error2 = ErrorResponse.builder()
                .code(null)
                .message("Error")
                .httpStatusCode(400)
                .build();
        assertEquals(error1, error2);
        assertEquals(error1.hashCode(), error2.hashCode());
    }

    @Test
    void testNotEqualsWhenOneFieldIsNull() {
        ErrorResponse error1 = ErrorResponse.builder()
                .code("ERR001")
                .message(null)
                .httpStatusCode(400)
                .build();
        ErrorResponse error2 = ErrorResponse.builder()
                .code("ERR001")
                .message("Error")
                .httpStatusCode(400)
                .build();
        assertNotEquals(error1, error2);
    }

    @Test
    void testDifferentHashCodesForDifferentObjects() {
        ErrorResponse error1 = ErrorResponse.builder()
                .code("A")
                .message("msg")
                .httpStatusCode(200)
                .build();
        ErrorResponse error2 = ErrorResponse.builder()
                .code("B")
                .message("msg")
                .httpStatusCode(200)
                .build();
        assertNotEquals(error1.hashCode(), error2.hashCode());
    }

    @Test
    void testAllFieldsNullStillValid() {
        ErrorResponse error = ErrorResponse.builder().build();
        assertNull(error.getCode());
        assertNull(error.getMessage());
        assertEquals(0, error.getHttpStatusCode());
        assertNotNull(error.toString());
    }

    @Test
    void testEqualsDifferentHttpStatusSameCodeAndMessage() {
        ErrorResponse error1 = ErrorResponse.builder()
                .code("ERR001")
                .message("Error")
                .httpStatusCode(400)
                .build();
        ErrorResponse error2 = ErrorResponse.builder()
                .code("ERR001")
                .message("Error")
                .httpStatusCode(401)
                .build();
        assertNotEquals(error1, error2);
    }

    @Test
    void testEqualsSameHttpStatusDifferentMessage() {
        ErrorResponse error1 = ErrorResponse.builder()
                .code("ERR001")
                .message("One")
                .httpStatusCode(400)
                .build();
        ErrorResponse error2 = ErrorResponse.builder()
                .code("ERR001")
                .message("Two")
                .httpStatusCode(400)
                .build();
        assertNotEquals(error1, error2);
    }

    @Test
    void testHashCodeConsistency() {
        ErrorResponse error = ErrorResponse.builder()
                .code("ERR123")
                .message("Consistent")
                .httpStatusCode(500)
                .build();
        int initial = error.hashCode();
        assertEquals(initial, error.hashCode());
    }


}
