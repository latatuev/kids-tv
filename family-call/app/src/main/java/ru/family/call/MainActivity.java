package ru.family.call;

import android.app.Activity;
import android.app.Dialog;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.HapticFeedbackConstants;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.Date;

public final class MainActivity extends Activity {
    final int INK = Color.rgb(25,33,47), BLUE=Color.rgb(46,100,205), GRAY=Color.rgb(98,110,128);
    Silence silence;
    LinearLayout list;

    @Override public void onCreate(Bundle state) { super.onCreate(state); silence = new Silence(this); showScreen(); }
    @Override public void onResume() { super.onResume(); if (silence!=null) showScreen(); }
    int dp(int n) { return (int)(n*getResources().getDisplayMetrics().density+0.5f); }
    GradientDrawable shape(int color,int r) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(r)); return d;
    }
    TextView text(String t,int sp,int color,boolean bold) {
        TextView v=new TextView(this);v.setText(t);v.setTextSize(sp);v.setTextColor(color);
        if(bold)v.setTypeface(null,Typeface.BOLD);return v;
    }
    void add(View v,int margin) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.bottomMargin=dp(margin);list.addView(v,p);
    }
    Button button(String label,boolean primary,Runnable callback) {
        Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextSize(16);
        b.setTextColor(primary?Color.WHITE:BLUE);
        b.setBackground(shape(primary?BLUE:0xffe6eefc,14));
        b.setOnClickListener(v->callback.run());return b;
    }
    void attempt(Runnable action) {
        try { action.run(); showScreen(); }
        catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Не получилось").setMessage(e.getMessage())
                .setPositiveButton("Понятно",null).show();
            showScreen();
        }
    }
    String clock(long ts) { return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(ts)); }
    String length(int min) {
        int h=min/60,m=min%60;
        return h==0 ? m+" мин" : h+" ч"+(m>0?" "+m+" мин":"");
    }

    void openPicker() {
        Dialog dialog=new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22),dp(14),dp(22),dp(20));
        GradientDrawable background=new GradientDrawable();
        background.setColor(Color.WHITE);
        float r=dp(26);
        background.setCornerRadii(new float[]{r,r,r,r,0,0,0,0});
        root.setBackground(background);
        View bar=new View(this);bar.setBackground(shape(0xffd9e1ee,6));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(42),dp(5));
        bp.gravity=Gravity.CENTER_HORIZONTAL;bp.bottomMargin=dp(20);root.addView(bar,bp);
        root.addView(text("Своя длительность",23,INK,true));
        TextView hint=text("Прокрути часы и минуты",14,GRAY,false);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.setMargins(0,dp(5),0,dp(15));root.addView(hint,hp);

        NumberPicker hours=new NumberPicker(this);
        hours.setMinValue(0); hours.setMaxValue(23); hours.setValue(0);
        hours.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        hours.setContentDescription("Часы");
        NumberPicker minutes=new NumberPicker(this);
        minutes.setMinValue(0);minutes.setMaxValue(59);minutes.setValue(30);
        minutes.setWrapSelectorWheel(true);
        minutes.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        minutes.setContentDescription("Минуты");

        LinearLayout wheels=new LinearLayout(this);
        wheels.setGravity(Gravity.CENTER);wheels.setBackground(shape(0xfff3f6fc,18));
        for (int i=0;i<2;i++) {
            LinearLayout col=new LinearLayout(this);col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView label=text(i==0?"Часы":"Минуты",14,GRAY,false);label.setGravity(Gravity.CENTER);
            col.addView(label);
            col.addView(i==0?hours:minutes,new LinearLayout.LayoutParams(-1,dp(170)));
            wheels.addView(col,new LinearLayout.LayoutParams(0,-2,1f));
        }
        root.addView(wheels,new LinearLayout.LayoutParams(-1,-2));
        TextView chosen=text("",21,INK,true);chosen.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);
        cp.topMargin=dp(17);root.addView(chosen,cp);
        TextView ends=text("",14,GRAY,false);ends.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,-2);
        ep.topMargin=dp(5);ep.bottomMargin=dp(16);root.addView(ends,ep);
        Button confirm=button("Включить тишину",true,()->{
            int n=hours.getValue()*60+minutes.getValue();
            if(n>0){dialog.dismiss();attempt(()->silence.start(n));}
        });
        Runnable refresh=()->{
            int n=hours.getValue()*60+minutes.getValue();
            chosen.setText(n==0?"Выбери хотя бы минуту":length(n));
            ends.setText(n==0?"":"Звук вернётся в "+clock(System.currentTimeMillis()+n*60000L));
            confirm.setEnabled(n>0);confirm.setAlpha(n>0?1f:0.5f);
        };
        NumberPicker.OnValueChangeListener changed=(picker,old,now)->{
            picker.performHapticFeedback(Build.VERSION.SDK_INT>=29
                ? HapticFeedbackConstants.CLOCK_TICK:HapticFeedbackConstants.VIRTUAL_KEY);
            refresh.run();
        };
        hours.setOnValueChangedListener(changed);minutes.setOnValueChangedListener(changed);refresh.run();
        root.addView(confirm,new LinearLayout.LayoutParams(-1,dp(54)));
        Button cancel=button("Отмена",false,dialog::dismiss);
        LinearLayout.LayoutParams cancelP=new LinearLayout.LayoutParams(-1,dp(48));
        cancelP.topMargin=dp(8);root.addView(cancel,cancelP);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);
        dialog.setContentView(scroll);
        Window w=dialog.getWindow();
        if(w!=null){
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(0.4f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        dialog.show();
        if(w!=null)w.setLayout(-1,-2);
    }

    void showScreen() {
        silence.reconcile();
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xfff6f8fb);
        list=new LinearLayout(this);list.setPadding(dp(20),dp(28),dp(20),dp(24));
        list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);
        add(text("Семейный звонок",27,INK,true),8);
        add(text("Тихо на время — звук вернётся автоматически",14,GRAY,false),20);
        TextView status=text("",18,INK,true);
        status.setPadding(dp(15),dp(18),dp(15),dp(18));
        status.setBackground(shape(0xffe8edf7,15));
        if(silence.active()) status.setText("🔕 Тихо до "+clock(silence.until()));
        else status.setText(silence.mode()==0?"Звук выключен вручную":
            silence.mode()==1?"Режим вибрации":"Звук включён");
        add(status,20);
        add(text("Выключить звук на:",17,INK,true),12);
        int[] presets={30,60,120,180,240};
        String[] labels={"30 минут","1 час","2 часа","3 часа","4 часа","Своя длительность…"};
        for(int i=0;i<3;i++) {
            LinearLayout row=new LinearLayout(this);
            for(int j=0;j<2;j++){
                int k=i*2+j;
                final int minutes=k<5?presets[k]:0;
                Button btn=button(labels[k],k==0,()->{
                    if(minutes==0)openPicker();else attempt(()->silence.start(minutes));
                });
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(54),1f);
                p.setMargins(dp(2),0,dp(4),0);row.addView(btn,p);
            }
            add(row,8);
        }
        if(silence.active())add(button("Вернуть звук сейчас",false,()->attempt(()->silence.stop())),12);
        add(text("Добавь кнопку «Тихо 30 мин» в шторку быстрых настроек.",14,GRAY,false),20);
        add(text("Разрешения",17,INK,true),10);
        add(text((silence.policyAccess()?"✓ Управление тишиной":"• Доступ к режиму тишины нужен")+
            "\n"+(silence.exactAccess()?"✓ Точные таймеры":"• Доступ к точным таймерам нужен"),14,GRAY,false),12);
        if(!silence.policyAccess())add(button("Разрешить режим тишины",false,()->
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))),10);
        if(!silence.exactAccess()&&Build.VERSION.SDK_INT>=31)add(button("Разрешить точные таймеры",false,()->{
            try{
                Intent intent=new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                intent.setData(android.net.Uri.parse("package:"+getPackageName()));
                startActivity(intent);
            }catch(Exception e){new AlertDialog.Builder(this).setMessage("Открой настройки приложения → Будильники и напоминания.").setPositiveButton("OK",null).show();}
        }),10);
        add(text("Пробная версия: локальный таймер. Обмен статусами и экстренный сигнал ещё не подключены.",13,GRAY,false),0);
        setContentView(scroll);
    }
}
