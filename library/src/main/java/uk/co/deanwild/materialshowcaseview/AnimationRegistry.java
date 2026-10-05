package uk.co.deanwild.materialshowcaseview;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.View;
import java.util.*;

final class AnimationRegistry {
    private final Map<View, List<Animator>> running = new IdentityHashMap<>();
    void start(View view, Animator animator) {
        List<Animator> list = running.get(view);
        if (list == null) { list = new ArrayList<>(); running.put(view, list); }
        list.add(animator);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                List<Animator> current = running.get(view);
                if (current != null) { current.remove(a); if (current.isEmpty()) running.remove(view); }
            }
        }); animator.start();
    }
    void cancel(View view) {
        List<Animator> list = running.remove(view);
        if (list != null) for (Animator a : list) { a.removeAllListeners(); a.cancel(); }
    }
}
