package com.cloudcore.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FragmentCodecTest {

    private static final int FRAGMENT_ID = 0xBEEF;

    @Test
    void reassemblesLargeUncompressedMessageInOrder() {
        byte[] body = pattern(100_000);
        Frame logical = new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_DEFINITION, body);
        List<Frame> fragments = Fragmenter.fragment(logical, Limits.S2C, FRAGMENT_ID);

        assertTrue(fragments.size() > 1);
        for (Frame fragment : fragments) {
            assertTrue((fragment.flags() & FrameFlags.FRAGMENT) != 0);
        }

        FragmentAssembler assembler = new FragmentAssembler(Limits.S2C);
        Frame complete = feed(assembler, fragments).orElseThrow();
        assertArrayEquals(body, complete.body());
        assertEquals(0, complete.flags());
        assertEquals(Opcode.UI_DEFINITION, complete.opcode());
    }

    @Test
    void acceptsFragmentsOutOfOrder() {
        byte[] body = pattern(80_000);
        Frame logical = new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_DEFINITION, body);
        List<Frame> fragments = new ArrayList<>(Fragmenter.fragment(logical, Limits.S2C, FRAGMENT_ID));
        Collections.reverse(fragments);

        Frame complete = feed(new FragmentAssembler(Limits.S2C), fragments).orElseThrow();
        assertArrayEquals(body, complete.body());
    }

    @Test
    void reassemblesCompressedFragmentStream() {
        byte[] body = new byte[400_000];
        Random random = new Random(42);
        random.nextBytes(body);
        Arrays.fill(body, 200_000, body.length, (byte) 0);

        Frame logical = new Frame(ProtocolVersion.MAJOR, FrameFlags.COMPRESSED,
                Opcode.UI_DEFINITION, body);
        List<Frame> fragments = Fragmenter.fragment(logical, Limits.S2C, FRAGMENT_ID);
        assertTrue(fragments.size() > 1);
        for (Frame fragment : fragments) {
            assertTrue((fragment.flags() & FrameFlags.COMPRESSED) != 0);
        }

        Frame complete = feed(new FragmentAssembler(Limits.S2C), fragments).orElseThrow();
        assertEquals(FrameFlags.COMPRESSED, complete.flags());
        assertArrayEquals(body, complete.body());
    }

    @Test
    void duplicateFragmentWithSameContentIsIgnored() {
        byte[] body = pattern(80_000);
        Frame logical = new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_DEFINITION, body);
        List<Frame> fragments = Fragmenter.fragment(logical, Limits.S2C, FRAGMENT_ID);
        FragmentAssembler assembler = new FragmentAssembler(Limits.S2C);

        List<Frame> withoutLast = fragments.subList(0, fragments.size() - 1);
        feed(assembler, withoutLast);
        assertTrue(assembler.accept(fragments.get(0)).isEmpty());
        Frame complete = assembler.accept(fragments.get(fragments.size() - 1)).orElseThrow();
        assertArrayEquals(body, complete.body());
    }

    @Test
    void duplicateFragmentWithDifferentContentAbortsGroup() {
        byte[] body = pattern(80_000);
        Frame logical = new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_DEFINITION, body);
        List<Frame> fragments = Fragmenter.fragment(logical, Limits.S2C, FRAGMENT_ID);
        FragmentAssembler assembler = new FragmentAssembler(Limits.S2C);
        assembler.accept(fragments.get(0));

        byte[] conflicting = fragments.get(0).body().clone();
        conflicting[conflicting.length - 1] ^= 0x7F;
        Frame mutated = new Frame(fragments.get(0).version(), fragments.get(0).flags(),
                fragments.get(0).opcode(), conflicting);

        assertThrows(ProtocolException.class, () -> assembler.accept(mutated));
        assertEquals(0, assembler.activeGroupCount());
    }

    @Test
    void conflictingGroupMetadataAbortsGroup() {
        Frame first = new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_DEFINITION, pattern(80_000));
        Frame second = new Frame(ProtocolVersion.MAJOR, 0, Opcode.TEXTURE_PUT, pattern(80_000));
        List<Frame> firstFragments = Fragmenter.fragment(first, Limits.S2C, FRAGMENT_ID);
        List<Frame> secondFragments = Fragmenter.fragment(second, Limits.S2C, FRAGMENT_ID);

        FragmentAssembler assembler = new FragmentAssembler(Limits.S2C);
        assembler.accept(firstFragments.get(0));
        assertThrows(ProtocolException.class, () -> assembler.accept(secondFragments.get(0)));
        assertEquals(0, assembler.activeGroupCount());
    }

    @Test
    void expiresGroupAfterTimeout() {
        Frame first = new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_DEFINITION, pattern(80_000));
        List<Frame> fragments = Fragmenter.fragment(first, Limits.S2C, FRAGMENT_ID);
        FragmentAssembler assembler = new FragmentAssembler(Limits.S2C, 100, 4);

        assembler.accept(fragments.get(0), 1_000);
        assertEquals(1, assembler.activeGroupCount());
        assembler.accept(fragments.get(1), 1_200);
        assertEquals(1, assembler.activeGroupCount());
        assertEquals(Optional.empty(), feedAt(assembler, fragments.subList(2, fragments.size()), 1_200));
        assertTrue(assembler.activeGroupCount() == 1);
    }

    @Test
    void rejectsMoreThanFourConcurrentGroups() {
        FragmentAssembler assembler = new FragmentAssembler(Limits.S2C);
        for (int id = 0; id < 4; id++) {
            Frame logical = new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_DEFINITION, pattern(80_000));
            assembler.accept(Fragmenter.fragment(logical, Limits.S2C, id).get(0));
        }
        assertEquals(4, assembler.activeGroupCount());

        Frame fifth = new Frame(ProtocolVersion.MAJOR, 0, Opcode.UI_DEFINITION, pattern(80_000));
        assertThrows(ProtocolException.class,
                () -> assembler.accept(Fragmenter.fragment(fifth, Limits.S2C, 4).get(0)));
        assertEquals(4, assembler.activeGroupCount());
    }

    @Test
    void rejectsOversizedDeclaredTotalLength() {
        ArrayByteSink sink = new ArrayByteSink();
        sink.writeShort(FRAGMENT_ID);
        sink.writeByte(0);
        sink.writeByte(2);
        sink.writeVarInt(Limits.S2C.maxDecompressedBytes() + 1);
        sink.writeVarInt(0);
        Frame fragment = new Frame(ProtocolVersion.MAJOR, FrameFlags.FRAGMENT,
                Opcode.UI_DEFINITION, sink.toByteArray());

        FragmentAssembler assembler = new FragmentAssembler(Limits.S2C);
        assertThrows(ProtocolException.class, () -> assembler.accept(fragment));
    }

    @Test
    void rejectsSingleFragmentFlag() {
        ArrayByteSink sink = new ArrayByteSink();
        sink.writeShort(FRAGMENT_ID);
        sink.writeByte(0);
        sink.writeByte(1);
        sink.writeVarInt(0);
        sink.writeVarInt(0);
        Frame fragment = new Frame(ProtocolVersion.MAJOR, FrameFlags.FRAGMENT,
                Opcode.UI_DEFINITION, sink.toByteArray());

        // FrameCodec permits the envelope; FragmentAssembler enforces the semantic rule.
        byte[] encoded = FrameCodec.encode(fragment, Limits.S2C);
        Frame decoded = FrameCodec.decode(encoded, Limits.S2C);
        assertThrows(ProtocolException.class,
                () -> new FragmentAssembler(Limits.S2C).accept(decoded));
    }

    @Test
    void randomFramesOnlyThrowProtocolException() {
        Random random = new Random(0xCC);
        for (int i = 0; i < 10_000; i++) {
            byte[] data = new byte[random.nextInt(256)];
            random.nextBytes(data);
            try {
                FrameCodec.decode(data, Limits.C2S);
            } catch (ProtocolException expected) {
                // Malformed input is reported as a protocol error and never escapes as another type.
            } catch (RuntimeException unexpected) {
                throw new AssertionError("Unexpected runtime failure at iteration " + i, unexpected);
            }
        }
    }

    private static Optional<Frame> feed(FragmentAssembler assembler, List<Frame> fragments) {
        Optional<Frame> result = Optional.empty();
        for (Frame fragment : fragments) {
            Optional<Frame> next = assembler.accept(fragment);
            if (next.isPresent()) {
                assertFalse(result.isPresent(), "Assembler returned more than one complete frame");
                result = next;
            }
        }
        return result;
    }

    private static Optional<Frame> feedAt(FragmentAssembler assembler, List<Frame> fragments, long now) {
        Optional<Frame> result = Optional.empty();
        for (Frame fragment : fragments) {
            Optional<Frame> next = assembler.accept(fragment, now);
            if (next.isPresent()) {
                result = next;
            }
        }
        return result;
    }

    private static byte[] pattern(int length) {
        byte[] body = new byte[length];
        for (int i = 0; i < length; i++) {
            body[i] = (byte) (i * 31 + 17);
        }
        return body;
    }
}
