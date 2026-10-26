package com.cloudcore.protocol;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Random bytes must only ever produce {@link ProtocolException} from the client-facing
 * decoders; anything else escaping would be an unchecked crash path.
 */
class ClientMessageFuzzTest {

    @Test
    void randomBytesOnlyRaiseProtocolExceptions() {
        Random random = new Random(20261027L);
        for (int index = 0; index < 200_000; index++) {
            byte[] data = new byte[random.nextInt(48)];
            random.nextBytes(data);
            probe("EVENT", () -> EventCodec.decode(data, Limits.C2S));
            probe("EVENT_RESULT", () -> EventResultCodec.decode(data, Limits.S2C));
            probe("TEXTURE_REF", () -> TextureRefCodec.decode(data, Limits.S2C));
        }
    }

    private static void probe(String what, Runnable decoder) {
        try {
            decoder.run();
        } catch (ProtocolException expected) {
            // Rejected input: fine.
        } catch (RuntimeException unexpected) {
            fail(what + " leaked " + unexpected.getClass().getName() + ": "
                    + unexpected.getMessage());
        }
    }
}
