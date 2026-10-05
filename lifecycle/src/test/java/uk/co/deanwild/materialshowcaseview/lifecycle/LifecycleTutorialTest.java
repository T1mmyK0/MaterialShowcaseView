package uk.co.deanwild.materialshowcaseview.lifecycle;

import android.app.Activity;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.lifecycle.*;
import androidx.activity.*;
import androidx.recyclerview.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import uk.co.deanwild.materialshowcaseview.session.*;
import java.time.Duration;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = {24, 28})
public class LifecycleTutorialTest {
    ActivityController<Activity> controller; Activity activity; FrameLayout root; Button target;
    static class Owner implements LifecycleOwner {
        final LifecycleRegistry lifecycle=new LifecycleRegistry(this);
        public Lifecycle getLifecycle(){return lifecycle;}
        void state(Lifecycle.State state){lifecycle.setCurrentState(state);}
    }
    Owner owner; TutorialCoordinator coordinator; TutorialSession session; AndroidTutorialHost host;
    @Before public void setup(){
        controller=Robolectric.buildActivity(Activity.class).setup().visible();activity=controller.get();
        root=new FrameLayout(activity);target=new Button(activity);root.addView(target,new FrameLayout.LayoutParams(160,80));activity.setContentView(root);layout();controller.windowFocusChanged(true);
        // Manual layout in this fixture does not dispatch a WindowManager visibility event.
        Object attachInfo=org.robolectric.util.ReflectionHelpers.getField(activity.getWindow().getDecorView(), "mAttachInfo");
        org.robolectric.util.ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE);
        owner=new Owner();owner.state(Lifecycle.State.CREATED);coordinator=new TutorialCoordinator();
        host=new AndroidTutorialHost(activity.getWindow(),id->target);TutorialTheme theme=new TutorialTheme();theme.reducedMotion=true;host.setTheme(theme);
        session=new TutorialSession(new Tutorial("tour",1,Step.builder("a").target("a").build()),host,new MainThreadScheduler(),null,coordinator,TutorialCoordinator.Conflict.QUEUE);
    }
    void layout(){View view=activity.getWindow().getDecorView();view.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(470,View.MeasureSpec.EXACTLY));view.layout(0,0,320,470);}
    void settle(){for(int i=0;i<6;i++){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));layout();root.getViewTreeObserver().dispatchOnPreDraw();}}
    @After public void destroy(){coordinator.cancel();controller.pause().stop().destroy();}
    @Test public void synchronousRecyclerLayoutCompletesPreparationOnlyOnce() {
        root.removeAllViews();
        RecyclerView list = new RecyclerView(activity) {
            @Override public void scrollToPosition(int position) {
                super.scrollToPosition(position);
                LifecycleTutorialTest.this.layout();
            }
        };
        list.setLayoutManager(new LinearLayoutManager(activity));
        root.addView(list, new FrameLayout.LayoutParams(-1, -1));
        RecyclerView.Adapter<RecyclerView.ViewHolder> adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            public long getItemId(int position) { return position; }
            public int getItemCount() { return 40; }
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                TextView text = new TextView(activity); text.setLayoutParams(new RecyclerView.LayoutParams(-1, 100));
                return new RecyclerView.ViewHolder(text) { };
            }
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) { }
        };
        adapter.setHasStableIds(true); list.setAdapter(adapter); layout();
        RecyclerViewTarget item = new RecyclerViewTarget(list, 35, 0); assertNull(item.resolve());
        Scope scope = new Scope(); int[] ready = {0};
        try {
            scope.own(item.prepare(scope, () -> ready[0]++));
            assertNotNull(item.resolve());
            assertEquals("Attachment during scroll must not complete preparation twice", 1, ready[0]);
            settle(); adapter.notifyDataSetChanged(); settle(); assertEquals(1, ready[0]);
        } finally { scope.cancel(); }
    }

    @Test public void onlyResumedOwnerDisplaysAndDestroyedViewDisposes(){
        new LifecycleTutorial(owner,host,session);session.start();assertEquals(TutorialSession.State.PAUSED,session.getState());
        owner.state(Lifecycle.State.RESUMED);settle();assertTrue("ready="+host.ready()+" shown="+activity.getWindow().getDecorView().isShown()+" visibility="+activity.getWindow().getDecorView().getWindowVisibility()+" focus="+activity.getWindow().getDecorView().hasWindowFocus(),host.ready());assertEquals(TutorialSession.State.SHOWING,session.getState());
        owner.state(Lifecycle.State.STARTED);assertEquals(TutorialSession.State.PAUSED,session.getState());
        owner.state(Lifecycle.State.RESUMED);settle();assertTrue("ready="+host.ready()+" shown="+activity.getWindow().getDecorView().isShown()+" visibility="+activity.getWindow().getDecorView().getWindowVisibility()+" focus="+activity.getWindow().getDecorView().hasWindowFocus(),host.ready());assertEquals(TutorialSession.State.SHOWING,session.getState());
        owner.state(Lifecycle.State.DESTROYED);assertEquals(0,coordinator.pendingCount());assertEquals(TutorialSession.State.CANCELLED,session.getState());
        try{session.start();fail();}catch(IllegalStateException expected){}
    }
    @Test public void predictiveCancelDoesNotCommitButBackDoes(){
        OnBackPressedDispatcher dispatcher=new OnBackPressedDispatcher();new LifecycleTutorial(owner,host,session).interceptBack(dispatcher,owner);owner.state(Lifecycle.State.RESUMED);session.start();settle();
        assertTrue(dispatcher.hasEnabledCallbacks());dispatcher.dispatchOnBackStarted(new BackEventCompat(0,0,0,BackEventCompat.EDGE_LEFT));dispatcher.dispatchOnBackCancelled();assertEquals(TutorialSession.State.SHOWING,session.getState());
        dispatcher.onBackPressed();assertEquals(TutorialSession.State.CANCELLED,session.getState());assertFalse(dispatcher.hasEnabledCallbacks());
    }
    @Test public void stableRecyclerIdentityResolvesAfterScrollingAndReorder(){
        root.removeAllViews();RecyclerView list=new RecyclerView(activity);list.setLayoutManager(new LinearLayoutManager(activity));root.addView(list,new FrameLayout.LayoutParams(-1,-1));
        class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>{
            boolean reversed;
            Adapter(){setHasStableIds(true);}
            public long getItemId(int p){return reversed?39-p:p;}
            public int getItemCount(){return 40;}
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent,int type){TextView text=new TextView(activity);text.setLayoutParams(new RecyclerView.LayoutParams(-1,100));return new RecyclerView.ViewHolder(text){};}
            public void onBindViewHolder(RecyclerView.ViewHolder holder,int position){((TextView)holder.itemView).setText("item "+getItemId(position));}
        }
        Adapter adapter=new Adapter();list.setAdapter(adapter);layout();RecyclerViewTarget item=new RecyclerViewTarget(list,35,0);assertNull(item.resolve());
        Scope scope=new Scope();int[] ready={0};scope.own(item.prepare(scope,()->ready[0]++));settle();assertNotNull(item.resolve());assertEquals("item 35",((TextView)item.resolve()).getText().toString());assertEquals(1,ready[0]);scope.cancel();
        adapter.reversed=true;adapter.notifyDataSetChanged();layout();Scope next=new Scope();next.own(item.prepare(next,()->{}));settle();assertEquals("item 35",((TextView)item.resolve()).getText().toString());next.cancel();
    }

    @Test public void bindingBackDuringExitStillInterceptsCancellation() {
        TutorialTheme theme = new TutorialTheme(); theme.animationMillis = 500; host.setTheme(theme);
        LifecycleTutorial binding = new LifecycleTutorial(owner, host, session);
        owner.state(Lifecycle.State.RESUMED); session.start(); settle(); session.next();
        assertEquals(TutorialSession.State.HIDING, session.getState());
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher(); binding.interceptBack(dispatcher, owner);
        assertTrue(dispatcher.hasEnabledCallbacks()); dispatcher.onBackPressed();
        assertEquals(TutorialSession.State.CANCELLED, session.getState());
        assertTrue(session.getProgress().completed.isEmpty());
    }

    @Test public void terminalListenerCanDisposeBackBindingDuringDispatch() {
        LifecycleTutorial[] binding = {null};
        session.addListener(event -> {
            if (event.state == TutorialSession.State.CANCELLED) binding[0].cancel();
        });
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher();
        binding[0] = new LifecycleTutorial(owner, host, session).interceptBack(dispatcher, owner);
        owner.state(Lifecycle.State.RESUMED); session.start(); settle();
        assertTrue(dispatcher.hasEnabledCallbacks());
        session.cancel(TutorialSession.Reason.USER);
        assertFalse(dispatcher.hasEnabledCallbacks()); assertEquals(0, coordinator.pendingCount());
    }
}
