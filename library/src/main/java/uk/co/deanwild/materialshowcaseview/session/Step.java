package uk.co.deanwild.materialshowcaseview.session;

/** Immutable, view-free step definition. Application predicates are evaluated when reached. */
public final class Step {
    public enum Unavailable { WAIT, PAUSE, CANCEL, SKIP_OPTIONAL, FAIL }
    public enum Interaction { NEXT, BACKGROUND_TAP, TARGET_ACTION, APPLICATION_ACTION, HINT }
    public enum Timeout { PAUSE, CANCEL, SKIP_OPTIONAL, FAIL }
    public interface Condition { boolean test(); }
    public interface Branch { String next(); }
    public final String id, targetId;
    public final CharSequence title, text;
    public final boolean optional, enabledRequired, clickableRequired;
    public final Condition condition;
    public final Branch branch;
    public final Unavailable unavailable;
    public final Interaction interaction;
    public final long delayMillis, timeoutMillis;
    public final Timeout timeout;
    public final java.util.List<String> additionalTargetIds;
    private Step(Builder b) {
        id = b.id; targetId = b.targetId; title = b.title == null ? "" : b.title.toString(); text = b.text == null ? "" : b.text.toString();
        optional = b.optional; enabledRequired = b.enabled; clickableRequired = b.clickable;
        condition = b.condition; branch = b.branch; unavailable = b.unavailable;
        interaction = b.interaction; delayMillis = b.delay; timeoutMillis = b.timeout;
        timeout = b.timeoutPolicy;
        additionalTargetIds = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(b.additional));
    }
    public static Builder builder(String id) { return new Builder(id); }
    public static final class Builder {
        private final String id;
        private String targetId;
        private CharSequence title = "", text = "";
        private boolean optional, enabled, clickable;
        private Condition condition = () -> true;
        private Branch branch;
        private Unavailable unavailable = Unavailable.WAIT;
        private Interaction interaction = Interaction.NEXT;
        private long delay, timeout = 15000;
        private Timeout timeoutPolicy = Timeout.FAIL;
        private final java.util.List<String> additional = new java.util.ArrayList<>();
        public Builder(String id) { if (id == null || id.isEmpty()) throw new IllegalArgumentException("step ID"); this.id = id; }
        public Builder target(String id) { targetId = id; return this; }
        /** Additional informational regions; primary target remains the navigation/scroll anchor. */
        public Builder highlight(String id) { if (id == null || id.isEmpty()) throw new IllegalArgumentException("target ID"); additional.add(id); return this; }
        public Builder content(CharSequence title, CharSequence text) { this.title = title; this.text = text; return this; }
        public Builder optional(boolean value) { optional = value; return this; }
        public Builder when(Condition value) { condition = value; return this; }
        public Builder branch(Branch value) { branch = value; return this; }
        public Builder requireEnabled(boolean value) { enabled = value; return this; }
        public Builder requireClickable(boolean value) { clickable = value; return this; }
        public Builder unavailable(Unavailable value) { unavailable = value; return this; }
        public Builder interaction(Interaction value) { interaction = value; return this; }
        public Builder delay(long value) { if (value < 0) throw new IllegalArgumentException("delay"); delay = value; return this; }
        public Builder timeout(long value) { if (value <= 0) throw new IllegalArgumentException("timeout"); timeout = value; return this; }
        public Builder onTimeout(Timeout value) { if (value == null) throw new IllegalArgumentException("timeout policy"); timeoutPolicy = value; return this; }
        public Step build() { if (condition == null || unavailable == null || interaction == null) throw new IllegalArgumentException("null option"); return new Step(this); }
    }
}
