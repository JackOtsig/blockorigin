package origin.jack.blockorigin.internal;

import origin.jack.blockorigin.api.BlockCause;
import origin.jack.blockorigin.api.BlockOrigin;

/**
 * Thread-local stack of active causes. The top of the stack is the cause that
 * will be stamped onto block changes by the {@code setBlockState} mixin.
 *
 * <p>Backed by an array per thread that grows as needed. Push/peek/pop are
 * allocation-free in the steady state; only a deeper-than-default stack
 * triggers an array resize. Frames are reusable singletons-per-thread so
 * try-with-resources doesn't allocate either.
 */
public final class CauseStack {

    private static final int INITIAL_CAPACITY = 16;

    private static final ThreadLocal<CauseStack> LOCAL = ThreadLocal.withInitial(CauseStack::new);

    public static CauseStack get() {
        return LOCAL.get();
    }

    private BlockCause[] frames = new BlockCause[INITIAL_CAPACITY];
    private int depth = 0;
    private final ReusableFrame frame = new ReusableFrame();

    private CauseStack() {}

    public BlockCause peek() {
        return depth == 0 ? BlockCause.UNKNOWN : frames[depth - 1];
    }

    public BlockOrigin.CauseFrame push(BlockCause cause) {
        if (depth == frames.length) {
            BlockCause[] grown = new BlockCause[frames.length * 2];
            System.arraycopy(frames, 0, grown, 0, depth);
            frames = grown;
        }
        frames[depth++] = cause;
        return frame;
    }

    /** Unbalanced pop, for callers that pushed without keeping a frame. */
    public void pop() {
        if (depth == 0) return;
        frames[--depth] = null;
    }

    private final class ReusableFrame implements BlockOrigin.CauseFrame {
        @Override public void close() { pop(); }
    }
}
