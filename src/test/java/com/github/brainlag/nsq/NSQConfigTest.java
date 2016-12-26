package com.github.brainlag.nsq;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class NSQConfigTest {

    @Test
    public void testNSQConfigDefaultValues() {
        NSQConfig config = new NSQConfig();

        assertEquals(
                "Expected HeartbeatInterval default to be 30 seconds",
                (int) TimeUnit.SECONDS.toMillis(30),
                config.getHeartbeatInterval()
        );
    }
}
