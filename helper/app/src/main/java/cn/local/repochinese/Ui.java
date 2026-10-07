package cn.local.repochinese;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;

final class Ui {
    static final int INK=Color.rgb(25,43,38), GREEN=Color.rgb(23,61,52), PAPER=Color.rgb(244,247,241);
    static int dp(Context c, int n) { return Math.round(n*c.getResources().getDisplayMetrics().density); }
    static LinearLayout column(Context c) {
        LinearLayout l=new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(c,20),dp(c,16),dp(c,20),dp(c,16)); l.setBackgroundColor(PAPER); return l;
    }
    static TextView text(Context c,String s,int size) {
        TextView t=new TextView(c); t.setText(s); t.setTextSize(size); t.setTextColor(INK);
        t.setPadding(0,dp(c,7),0,dp(c,7)); t.setLineSpacing(dp(c,3),1); return t;
    }
    static TextView title(Context c,String s) {TextView t=text(c,s,25);t.setTypeface(null,Typeface.BOLD);return t;}
    static Button button(Context c,String s) {
        Button b=new Button(c);b.setText(s);b.setTextSize(15);b.setAllCaps(false);b.setTextColor(Color.WHITE);
        GradientDrawable bg=new GradientDrawable();bg.setColor(GREEN);bg.setCornerRadius(dp(c,14));b.setBackground(bg);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(c,52));p.setMargins(0,dp(c,7),0,dp(c,7));b.setLayoutParams(p);return b;
    }
    static void selectable(TextView t) {t.setTextIsSelectable(true);}
}
