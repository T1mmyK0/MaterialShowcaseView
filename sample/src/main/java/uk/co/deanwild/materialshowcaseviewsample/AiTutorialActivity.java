package uk.co.deanwild.materialshowcaseviewsample;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.core.widget.NestedScrollView;
import uk.co.deanwild.materialshowcaseview.session.*;
import uk.co.deanwild.materialshowcaseview.lifecycle.LifecycleTutorial;

/** Dynamic rule-editor integration. No tutorial timers or per-step scroll controller. */
public class AiTutorialActivity extends SampleActivity {
    private final TutorialCoordinator aiGroup = new TutorialCoordinator();
    private TutorialSession gpt, gemini;
    private LifecycleTutorial replayBinding;
    private String provider = "chatgpt";
    private LinearLayout editor;
    private EditText prompt;
    private View importPrompt, tags, gptInfo, geminiInfo;
    private TextView status;
    private boolean hidden, policyRequired;
    private Cancellation testBlocker = Cancellation.NONE;
    private AlertDialog policyDialog;
    private Cancellation policyBlocker = Cancellation.NONE;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout screen = new LinearLayout(this); screen.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this); screen.addView(status);
        LinearLayout choices = new LinearLayout(this); screen.addView(choices);
        button(choices, "ChatGPT", () -> select("chatgpt", true));
        button(choices, "Gemini", () -> select("gemini", true));
        button(choices, "Other", () -> select("other", false));
        NestedScrollView outer = new NestedScrollView(this);
        editor = new LinearLayout(this); editor.setOrientation(LinearLayout.VERTICAL); outer.addView(editor);
        screen.addView(outer, new LinearLayout.LayoutParams(-1, 0, 1));
        button(editor, "Toggle targets", () -> { hidden = !hidden; prompt.setVisibility(hidden ? View.GONE : View.VISIBLE); invalidateSessions(); });
        button(editor, "Toggle enabled", () -> { prompt.setEnabled(!prompt.isEnabled()); invalidateSessions(); });
        button(editor, "Move / resize target", () -> { tags.setTranslationX(tags.getTranslationX() == 0 ? 40 : 0); tags.setMinimumHeight(tags.getHeight() + 20); invalidateSessions(); });
        button(editor, "Remove / restore info", () -> { if (gptInfo.getParent() != null) editor.removeView(gptInfo); else editor.addView(gptInfo); invalidateSessions(); });
        button(editor, "Long prompt", () -> { StringBuilder text = new StringBuilder(); for (int i = 0; i < 60; i++) text.append("Respond thoughtfully to the incoming message.\n"); prompt.setText(text); });
        button(editor, "Start blocked tutorial for target tests", () -> {
            TutorialSession session = selected(); if (session == null) return;
            cancelProviders(); testBlocker = session.block("manual-target-test"); startWithPolicy(session);
        });
        button(editor, "Release target-test blocker", () -> testBlocker.cancel());
        button(editor, "Replay selected tutorial", () -> {
            if (selected() == null) return;
            cancelProviders(); startWithPolicy(session(provider, true));
        });
        button(editor, "Reset selected progress", () -> {
            TutorialSession session = selected(); if (session == null) return;
            cancelProviders(); session.resetProgress(); startWithPolicy(session);
        });
        prompt = new EditText(this); prompt.setHint("reply_message_text: AI prompt"); prompt.setMinLines(5); editor.addView(prompt);
        importPrompt = button(editor, "no_reply: Import default prompt", () -> prompt.setText("Write a helpful reply."));
        tags = button(editor, "add_tags: Answer replacements", () -> prompt.append(" {message}"));
        TextView longContent = new TextView(this); longContent.setText(repeat("Long editor content\n\n",30)); editor.addView(longContent);
        ScrollView nested = new ScrollView(this); TextView nestedContent = new TextView(this); nestedContent.setText(repeat("Nested scrolling content\n",20)); nested.addView(nestedContent); editor.addView(nested, new LinearLayout.LayoutParams(-1, 150));
        gptInfo = button(editor, "info_gpt: API key information", () -> { });
        geminiInfo = button(editor, "info_gemini: API key information", () -> { });
        setContentView(screen);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(screen, (v, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.displayCutout() | androidx.core.view.WindowInsetsCompat.Type.ime());
            v.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(screen);
        // Simulates database first, unsaved draft second. Do not start between these stages.
        provider = "chatgpt";
        if (state != null) { provider = state.getString("provider", provider); prompt.setText(state.getString("prompt", "")); }
        gpt = session("chatgpt", false); gemini = session("gemini", false);
        select(provider, state != null && state.getBoolean("policy-required", false));
    }
    private TutorialSession session(String name, boolean replay) {
        boolean isGpt = name.equals("chatgpt");
        Tutorial definition = new Tutorial(isGpt ? "sc_gpt" : "sc_gemini", 1,
                step("prompt", "reply_message_text", "AI prompt", "Describe how the AI should answer."),
                step("default", "no_reply", "Default prompt", "Import the suggested prompt."),
                step("replacements", "add_tags", "Answer replacements", "Insert message and answer replacements."),
                step("api-key", isGpt ? "info_gpt" : "info_gemini", "API key", "Use this information button to obtain your provider's API key."));
        AndroidTutorialHost host = new AndroidTutorialHost(getWindow(), id -> {
            switch (id) {
                case "reply_message_text": return prompt;
                case "no_reply": return importPrompt;
                case "add_tags": return tags;
                case "info_gpt": return gptInfo;
                case "info_gemini": return geminiInfo;
                default: return null;
            }
        });
        host.setAlignment(AndroidTutorialHost.Alignment.NEAREST, 16);
        TutorialSession session = new TutorialSession(definition, host, new MainThreadScheduler(),
                new SharedPreferencesProgressStore(this), aiGroup, TutorialCoordinator.Conflict.REPLACE, replay);
        session.setEligibility(() -> provider.equals(name));
        session.setErrorHandler(error -> android.util.Log.e("AiTutorial", "Tutorial callback failed", error));
        session.addListener(event -> status.setText(event.tutorialId + ": " + event.state + " / " + event.reason));
        LifecycleTutorial binding = new LifecycleTutorial(this, host, session).interceptBack(getOnBackPressedDispatcher(), this);
        if (replay) replayBinding = binding;
        return session;
    }
    private Step step(String id, String target, String title, String text) {
        return Step.builder(id).target(target).content(title, text).requireEnabled(true).timeout(30000).build();
    }
    private void select(String name, boolean policy) {
        cancelProviders(); provider = name;
        policyRequired = policy;
        gptInfo.setVisibility(name.equals("chatgpt") ? View.VISIBLE : View.GONE);
        geminiInfo.setVisibility(name.equals("gemini") ? View.VISIBLE : View.GONE);
        TutorialSession session = selected();
        if (session != null) startWithPolicy(session);
    }
    private void startWithPolicy(TutorialSession session) {
        if (policyRequired) {
            Cancellation blocker = session.block("policy-dialog");
            final boolean[] accepted = {false};
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle(provider + " policy")
                    .setMessage("The tutorial waits for this dialog to close, however long that takes.")
                    .setPositiveButton("Accept", (d, which) -> {
                        if (policyDialog == d) { accepted[0] = true; policyRequired = false; }
                    })
                    .setNegativeButton("Cancel", (d, which) -> { }).create();
            dialog.setOnDismissListener(d -> {
                if (policyDialog != dialog) { blocker.cancel(); return; }
                policyDialog = null; policyBlocker = Cancellation.NONE;
                // Negative, Back, outside touch and programmatic dismissal all require acceptance.
                try { if (!accepted[0] && session.isActive()) session.cancel(TutorialSession.Reason.USER); }
                finally { blocker.cancel(); }
            });
            policyDialog = dialog; policyBlocker = blocker;
            dialog.show();
        }
        session.start();
    }
    private static String repeat(String text,int count){StringBuilder result=new StringBuilder();for(int i=0;i<count;i++)result.append(text);return result.toString();}
    private TutorialSession selected() { return provider.equals("chatgpt") ? gpt : provider.equals("gemini") ? gemini : null; }
    private void cancelProviders() {
        if (gpt != null) gpt.cancel(TutorialSession.Reason.INELIGIBLE);
        if (gemini != null) gemini.cancel(TutorialSession.Reason.INELIGIBLE);
        if (replayBinding != null) { replayBinding.cancel(); replayBinding = null; }
        testBlocker.cancel(); testBlocker = Cancellation.NONE;
        dismissPolicyDialog();
    }
    private void dismissPolicyDialog() {
        AlertDialog dialog = policyDialog; policyDialog = null;
        if (dialog != null) { dialog.setOnDismissListener(null); dialog.dismiss(); }
        policyBlocker.cancel(); policyBlocker = Cancellation.NONE;
    }
    private void invalidateSessions() { if (gpt != null) gpt.invalidateEligibility(); if (gemini != null) gemini.invalidateEligibility(); }
    private Button button(LinearLayout parent, String label, Runnable click) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setOnClickListener(v -> click.run()); parent.addView(button); return button;
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("provider", provider); state.putString("prompt", prompt.getText().toString());
        state.putBoolean("policy-required", policyRequired); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        try { aiGroup.cancel(); }
        finally { dismissPolicyDialog(); super.onDestroy(); }
    }
}
