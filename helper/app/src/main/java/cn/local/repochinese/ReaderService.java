package cn.local.repochinese;

import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import java.util.*;

public class ReaderService extends AccessibilityService {
    private WindowManager wm;
    private View floating,panel;
    private boolean github;
    private long generation;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable check=this::checkForeground;
    @Override protected void onServiceConnected(){wm=(WindowManager)getSystemService(WINDOW_SERVICE);handler.postDelayed(check,250);}
    @Override public void onAccessibilityEvent(AccessibilityEvent e){handler.removeCallbacks(check);handler.postDelayed(check,160);}
    private void checkForeground() {
        if(wm==null)return;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        boolean active=root!=null && "com.github.android".contentEquals(root.getPackageName()==null?"":root.getPackageName());
        if(root!=null)root.recycle();
        github=active;
        if(active && floating==null)showButton();
        if(!active)hideAll();
        handler.removeCallbacks(check);handler.postDelayed(check,700);
    }
    private WindowManager.LayoutParams params(int w,int h,int gravity) {
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
        p.gravity=gravity;return p;
    }
    private void showButton() {
        TextView b=Ui.text(this,"译为中文",14);b.setTextColor(Color.WHITE);b.setGravity(Gravity.CENTER);b.setPadding(Ui.dp(this,15),Ui.dp(this,12),Ui.dp(this,15),Ui.dp(this,12));
        GradientDrawable bg=new GradientDrawable();bg.setColor(Ui.GREEN);bg.setCornerRadius(Ui.dp(this,25));b.setBackground(bg);b.setElevation(Ui.dp(this,6));
        WindowManager.LayoutParams p=params(-2,-2,Gravity.END|Gravity.CENTER_VERTICAL);p.x=Ui.dp(this,8);
        final float[] start=new float[3];
        b.setOnTouchListener((v,event)->{
            if(event.getAction()==MotionEvent.ACTION_DOWN){start[0]=event.getRawY();start[1]=p.y;start[2]=0;return true;}
            if(event.getAction()==MotionEvent.ACTION_MOVE){float delta=event.getRawY()-start[0];if(Math.abs(delta)>Ui.dp(this,6))start[2]=1;p.y=(int)(start[1]+delta);if(floating!=null)wm.updateViewLayout(floating,p);return true;}
            if(event.getAction()==MotionEvent.ACTION_UP){if(start[2]==0)v.performClick();return true;}return true;
        });
        b.setOnClickListener(v->capture());floating=b;
        try{wm.addView(b,p);}catch(Exception e){floating=null;}
    }
    private void capture() {
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null || !"com.github.android".contentEquals(root.getPackageName()==null?"":root.getPackageName())){if(root!=null)root.recycle();hideAll();return;}
        LinkedHashSet<String> lines=new LinkedHashSet<>();collect(root,lines,0);root.recycle();
        StringBuilder s=new StringBuilder();for(String line:lines){if(s.length()>0)s.append("\n\n");s.append(line);}
        openPanel(s.length()==0?null:s.toString());
    }
    private void collect(AccessibilityNodeInfo n,LinkedHashSet<String> lines,int depth) {
        if(depth>45 || lines.size()>120 || n.isPassword() || n.isEditable() || !n.isVisibleToUser())return;
        String viewId=n.getViewIdResourceName();
        if(viewId!=null && (viewId.startsWith("BottomNavigationItem_") || viewId.contains("bottom_navigation")))return;
        CharSequence t=n.getText();if((t==null || t.toString().isBlank()) && n.getChildCount()==0)t=n.getContentDescription();
        if(t!=null) {
            String s=t.toString().trim();Rect bounds=new Rect();n.getBoundsInScreen(bounds);
            boolean heading=s.matches("(?i)readme|installation|usage|features|license|requirements|documentation|building|setup|overview|configuration|contributing|examples|support");
            boolean singleToken=s.matches("[^\\s]+");
            if(!bounds.isEmpty() && TextTools.english(s) && s.length()>2 && (!singleToken || heading)) {
                lines.add(s.length()>18000?s.substring(0,18000):s);
                if(n.getText()!=null && !n.getText().toString().isBlank())return;
            }
        }
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){collect(c,lines,depth+1);c.recycle();}}
    }
    private void openPanel(String input) {
        closePanel();long id=++generation;
        LinearLayout box=Ui.column(this);box.setElevation(Ui.dp(this,12));
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading=Ui.text(this,"当前页 · 中文译文",18);heading.setTypeface(null,android.graphics.Typeface.BOLD);top.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button close=new Button(this);close.setText("收起");top.addView(close);close.setOnClickListener(v->closePanel());box.addView(top);
        TextView hint=Ui.text(this,"翻译当前可见内容。收起后滚动 GitHub，再点按钮继续。",12);box.addView(hint);
        ScrollView scroll=new ScrollView(this);LinearLayout body=Ui.column(this);body.setPadding(0,0,0,0);scroll.addView(body);box.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView translated=Ui.text(this,input==null?"此页没有可读取的英文。请打开 README、项目介绍或讨论正文；图片文字暂不支持。":"正在本地翻译…",17);Ui.selectable(translated);body.addView(translated);
        if(input!=null){TextView original=Ui.text(this,"英文原文\n\n"+input,13);Ui.selectable(original);body.addView(original);}
        int height=getResources().getDisplayMetrics().heightPixels*3/5;
        WindowManager.LayoutParams p=params(-1,height,Gravity.BOTTOM);p.y=Ui.dp(this,26);panel=box;
        try{wm.addView(panel,p);}catch(Exception e){panel=null;return;}
        if(input!=null)Engine.get().translate(input,new Engine.Result(){public void done(String t){if(id==generation && panel!=null){translated.setText(t);hint.setText("✓ 本地翻译完成 · 收起后可继续滚动原文");}}public void error(String t){if(id==generation && panel!=null)translated.setText(t);}});
    }
    private void closePanel(){generation++;if(panel!=null){try{wm.removeView(panel);}catch(Exception ignored){}panel=null;}}
    private void hideAll(){closePanel();if(floating!=null){try{wm.removeView(floating);}catch(Exception ignored){}floating=null;}}
    @Override public void onInterrupt(){hideAll();}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);hideAll();super.onDestroy();}
}
