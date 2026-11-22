package com.frequalizer.layoutdesigner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.*;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MainActivity extends Activity {
    private final String[] modes = {"TABLET VERTICALE", "TABLET ORIZZONTALE", "S25 VERTICALE", "S25 ORIZZONTALE"};
    private final Map<String, JSONArray> layouts = new LinkedHashMap<>();
    private String mode = modes[0];
    private EditorView editor;
    private TextView status;
    private static final int EXPORT = 41;
    private String undoMode;
    private JSONArray undoSnapshot;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        for (String m : modes) layouts.put(m, new JSONArray());
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean phoneDevice = Math.min(dm.widthPixels, dm.heightPixels) / dm.density < 600f;
        boolean landscapeDevice = dm.widthPixels > dm.heightPixels;
        mode = phoneDevice ? (landscapeDevice ? modes[3] : modes[2]) : (landscapeDevice ? modes[1] : modes[0]);
        loadLocal();
        seedLayoutsIfEmpty();
        ensureDelayKeyboardGroups();
        constrainLayoutsToCanvas();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(28,30,34));
        LinearLayout top = new LinearLayout(this);
        top.setPadding(8,8,8,8);
        Spinner chooser = new Spinner(this);
        chooser.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modes));
        chooser.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                mode = modes[pos]; editor.invalidate(); updateStatus();
            }
        });
        top.addView(chooser, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(top);
        editor = new EditorView();
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setPadding(8,5,8,8);
        addTopButton(bottom, "RETURN", this::undoLast);
        addTopButton(bottom, "SALVA", this::saveLocal);
        addTopButton(bottom, "ESPORTA RECORD", this::export);
        root.addView(bottom);
        status = new TextView(this);
        status.setTextColor(Color.WHITE); status.setPadding(12,4,12,8);
        root.addView(status);
        setContentView(root);
        updateStatus();
    }

    private void addTopButton(LinearLayout row, String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setTextSize(10);
        b.setPadding(3,0,3,0); b.setOnClickListener(v -> action.run());
        row.addView(b, new LinearLayout.LayoutParams(0, 48, 1));
    }
    private JSONArray current() { return layouts.get(mode); }

    // Annulla l'ultima modifica effettuata, anche se apparteneva a un altro profilo.
    private void undoLast() {
        if (undoSnapshot == null || undoMode == null) {
            Toast.makeText(this, "Nessuna modifica da annullare", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            layouts.put(undoMode, new JSONArray(undoSnapshot.toString()));
            undoSnapshot = null;
            undoMode = null;
            if (editor != null) { editor.selected = -1; editor.invalidate(); }
            saveLocal();
            Toast.makeText(this, "Ultima modifica annullata", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    // Preload the real FREQUALIZER control map instead of opening on a blank canvas.
    private void seedLayoutsIfEmpty() {
        // Replace only the original blank-prototype project once. From this point
        // onward the user's edited layouts are always preserved across upgrades.
        if (getPreferences(0).getBoolean("frequalizer_template_seeded_v1", false)) return;
        for (String m : modes) layouts.put(m, new JSONArray());
        seedTabletPortrait(); seedTabletLandscape(); seedPhonePortrait(); seedPhoneLandscape();
        saveLocal();
        getPreferences(0).edit().putBoolean("frequalizer_template_seeded_v1", true).apply();
    }
    private void templateItem(String layout,String id,String type,String text,double x,double y,double w,double h,String color) {
        try {
            JSONObject o = new JSONObject();
            o.put("id",id); o.put("type",type); o.put("text",text);
            o.put("x",x); o.put("y",y); o.put("w",w); o.put("h",h); o.put("color",color);
            layouts.get(layout).put(o);
        } catch (Exception ignored) {}
    }
    private void tPanel(String l,String id,String title,double x,double y,double w,double h) { templateItem(l,id,"panel","",x,y,w,h,"#111317"); }
    private void tText(String l,String id,String title,double x,double y,double w,double h) { templateItem(l,id,"text",title,x,y,w,h,(id.endsWith("_title") || id.equals("eq_title") || id.equals("iso_title") || id.equals("filter_title") || id.equals("master_title")) ? "#DC464B" : "#FFFFFF"); }
    private void tButton(String l,String id,String title,double x,double y,double w,double h) { templateItem(l,id,"button",title,x,y,w,h,"#FFCD00"); }
    private void tKnob(String l,String id,String title,double x,double y,double w,double h,String color) { templateItem(l,id,"knob",title,x,y,w,h,color); }
    private void seedHeader(String l,boolean compact) {
        tButton(l,"app_power","● ON",.015,.012,.105,.035);
        tButton(l,"bpm_start","BPM START",compact ? .255 : .285,.012,compact ? .17 : .20,.034);
        tText(l,"bpm_readout","— BPM",compact ? .435 : .50,.012,.13,.034);
        tText(l,"bpm_beat","●",compact ? .58 : .68,.006,.045,.045);
        tButton(l,"options","⚙",.91,.008,.065,.042);
    }
    private void seedBandSection(String l,String key,String title,double x,double y,double w,double h,String[] bands,String color) {
        tPanel(l,key+"_panel",title,x,y,w,h);
        tText(l,key+"_title",title,x+.025*w,y+.015*h,.45*w,.10*h);
        tText(l,key+"_power","ON",x+.025*w,y+.035*h,.13*w,.075*h);
        tButton(l,key+"_flat","F",x+.82*w,y+.035*h,.13*w,.075*h);
        double rowH=(h*.79)/bands.length;
        for(int i=0;i<bands.length;i++) {
            double ry=y+h*.17+i*rowH;
            tText(l,key+"_label_"+i,bands[i],x+.025*w,ry,.25*w,rowH*.76);
            tKnob(l,key+"_knob_"+i,bands[i],x+.32*w,ry+.03*rowH,.28*w,rowH*.80,color);
            tButton(l,key+"_return_"+i,"↶",x+.68*w,ry+.20*rowH,.12*w,rowH*.56);
            tButton(l,key+"_flat_"+i,"F",x+.82*w,ry+.20*rowH,.13*w,rowH*.56);
        }
    }
    private void seedFilterSection(String l,double x,double y,double w,double h,String color) {
        tPanel(l,"filter_panel","HPF",x,y,w,h);
        tText(l,"filter_title","HPF",x+.04*w,y+.015*h,.40*w,.10*h);
        tButton(l,"filter_power","ON",x+.78*w,y+.025*h,.17*w,.085*h);
        String[] names={"HPF","RESONANCE"};
        for(int i=0;i<2;i++) {
            double ry=y+.15*h+i*.405*h;
            tText(l,"filter_label_"+i,names[i],x+.035*w,ry,.25*w,.25*h);
            tKnob(l,"filter_knob_"+i,names[i],x+.32*w,ry,.38*w,.31*h,color);
            tButton(l,"filter_return_"+i,"↶",x+.75*w,ry+.025*h,.095*w,.22*h);
            tButton(l,"filter_zero_"+i,"0",x+.855*w,ry+.025*h,.095*w,.22*h);
        }
    }
    private void seedMasterPortrait(String l,double x,double y,double w,double h,String color) {
        tPanel(l,"master_panel","MASTER",x,y,w,h);
        tText(l,"master_title","MASTER",x+.025*w,y+.015*h,.45*w,.10*h);
        String[] names={"GAIN","COMP","BASS BOOST","LOUDNESS"};
        for(int i=0;i<4;i++) {
            int col=i%2,row=i/2;
            double cx=x+col*w*.50, cy=y+.12*h+row*h*.43;
            tText(l,"master_label_"+i,names[i],cx+.025*w,cy,.43*w,.13*h);
            tKnob(l,"master_knob_"+i,names[i],cx+.12*w,cy+.12*h,.25*w,.25*h,color);
            if(i>0) tButton(l,"master_on_"+i,"ON",cx+.39*w,cy+.13*h,.095*w,.105*h);
            if(i>=2) tButton(l,"master_return_"+i,"↶",cx+.39*w,cy+.255*h,.095*w,.105*h);
        }
    }
    private void seedEffects(String l,double x,double y,double w,double h) {
        tPanel(l,"fx_panel","BEAT FX",x,y,w,h);
        double rw=w*.37, dx=x+rw;
        tText(l,"reverb_title","REVERB",x+.015*w,y+.015*h,.30*w,.10*h);
        tButton(l,"reverb_power","ON",x+.30*w,y+.02*h,.065*w,.075*h);
        String[] rn={"BEAT","TIME","DRY/WET","FILTER"};
        for(int i=0;i<4;i++) {
            int col=i%2,row=i/2;
            double kx=x+.015*w+col*rw*.49, ky=y+.13*h+row*h*.40;
            tKnob(l,"reverb_"+i,rn[i],kx,ky,rw*.44,h*.32,"#46BE78");
        }
        tText(l,"delay_title","DELAY / DUB ECHO",dx+.01*w,y+.015*h,.48*w,.10*h);
        tButton(l,"delay_power","ON",dx+.80*w,y+.02*h,.085*w,.075*h);
        String[] dn={"LEVEL","FEEDBACK","TONE","TIME"};
        for(int i=0;i<4;i++) {
            double kx=dx+.015*w+i*(w*.14), ky=y+.13*h;
            tKnob(l,"delay_knob_"+i,dn[i],kx,ky,w*.125,h*.30,"#46BE78");
        }
        tText(l,"delay_bpm","— BPM",dx+.015*w,y+.46*h,.12*w,.11*h);
        tButton(l,"delay_tap","TAP",dx+.145*w,y+.46*h,.10*w,.11*h);
        tButton(l,"delay_auto","AUTO",dx+.255*w,y+.46*h,.11*w,.11*h);
        tButton(l,"delay_lock","LOCK",dx+.375*w,y+.46*h,.11*w,.11*h);
        tButton(l,"delay_align","ALIGN",dx+.495*w,y+.46*h,.12*w,.11*h);
        tButton(l,"delay_back","◀",dx+.625*w,y+.46*h,.075*w,.11*h);
        tButton(l,"delay_forward","▶",dx+.71*w,y+.46*h,.075*w,.11*h);
        String[] divisions={"1/16","1/8","1/4","1/2","3/4","1","2","4","8","16"};
        for(int i=0;i<divisions.length;i++) tButton(l,"division_"+i,divisions[i],dx+.015*w+i*(w*.094),y+.60*h,w*.087,h*.12);
        tButton(l,"fx_low","FX LOW",dx+.015*w,y+.76*h,.29*w,.12*h);
        tButton(l,"fx_mid","FX MID",dx+.32*w,y+.76*h,.29*w,.12*h);
        tButton(l,"fx_high","FX HIGH",dx+.625*w,y+.76*h,.29*w,.12*h);
    }
    private void seedTabletPortrait() {
        String l=modes[0]; seedHeader(l,false);
        seedBandSection(l,"eq","EQ",.012,.065,.478,.305,new String[]{"HIGH","MID HIGH","MID LOW","LOW"},"#46BE78");
        seedBandSection(l,"iso","ISOLATOR",.510,.065,.478,.305,new String[]{"HIGH","MID","LOW"},"#46BE78");
        seedFilterSection(l,.012,.382,.478,.326,"#46BE78");
        seedMasterPortrait(l,.510,.382,.478,.326,"#46BE78");
        seedEffects(l,.012,.718,.976,.274);
    }
    private void seedTabletLandscape() {
        String l=modes[1]; seedHeader(l,false);
        seedBandSection(l,"eq","EQ",.012,.065,.478,.425,new String[]{"HIGH","MID HIGH","MID LOW","LOW"},"#46BE78");
        seedBandSection(l,"iso","ISOLATOR",.510,.065,.478,.425,new String[]{"HIGH","MID","LOW"},"#46BE78");
        seedFilterSection(l,.012,.505,.235,.475,"#46BE78");
        seedMasterPortrait(l,.255,.505,.235,.475,"#46BE78");
        seedEffects(l,.500,.505,.488,.475);
    }
    private void seedPhonePortrait() {
        String l=modes[2]; seedHeader(l,true);
        seedBandSection(l,"eq","EQ",.012,.065,.478,.425,new String[]{"HIGH","MID HIGH","MID LOW","LOW"},"#46BE78");
        seedBandSection(l,"iso","ISOLATOR",.510,.065,.478,.425,new String[]{"HIGH","MID","LOW"},"#46BE78");
        seedFilterSection(l,.012,.505,.235,.475,"#46BE78");
        seedMasterPortrait(l,.255,.505,.235,.475,"#46BE78");
        seedEffects(l,.500,.505,.488,.475);
    }
    private void seedPhoneLandscape() {
        String l=modes[3]; seedHeader(l,true);
        seedBandSection(l,"eq","EQ",.012,.065,.478,.425,new String[]{"HIGH","MID HIGH","MID LOW","LOW"},"#46BE78");
        seedBandSection(l,"iso","ISOLATOR",.510,.065,.478,.425,new String[]{"HIGH","MID","LOW"},"#46BE78");
        seedFilterSection(l,.012,.505,.235,.475,"#46BE78");
        seedMasterPortrait(l,.255,.505,.235,.475,"#46BE78");
        seedEffects(l,.500,.505,.488,.475);
    }


    // La tastiera delle divisioni DELAY è un unico gruppo indivisibile:
    // tasti e controlli contenuti si muovono e si ridimensionano insieme.
    private void ensureDelayKeyboardGroups() {
        for (String l : modes) {
            JSONArray old = layouts.get(l);
            boolean exists = false;
            for (int i=0;i<old.length();i++) {
                JSONObject o=old.optJSONObject(i);
                if(o!=null && "delay_keyboard_panel".equals(o.optString("id"))) { exists=true; break; }
            }
            if(exists) continue;
            try {
                double minX=1,minY=1,maxX=0,maxY=0;
                for(int i=0;i<old.length();i++) {
                    JSONObject item=old.optJSONObject(i);
                    if(item==null || !item.optString("id").startsWith("division_")) continue;
                    double x=item.optDouble("x"), y=item.optDouble("y");
                    minX=Math.min(minX,x); minY=Math.min(minY,y);
                    maxX=Math.max(maxX,x+item.optDouble("w"));
                    maxY=Math.max(maxY,y+item.optDouble("h"));
                }
                if(maxX<=minX || maxY<=minY) continue;
                double marginX=0.012, marginY=0.018;
                JSONObject group = new JSONObject();
                group.put("id","delay_keyboard_panel");
                group.put("type","panel");
                group.put("text","");
                group.put("x",Math.max(0,minX-marginX));
                group.put("y",Math.max(0,minY-marginY));
                group.put("w",Math.min(1, maxX+marginX)-Math.max(0,minX-marginX));
                group.put("h",Math.min(1, maxY+marginY)-Math.max(0,minY-marginY));
                group.put("color","#252A31");
                JSONArray updated = new JSONArray();
                boolean inserted=false;
                for(int i=0;i<old.length();i++) {
                    JSONObject item=old.optJSONObject(i);
                    if(item!=null && item.optString("id").startsWith("division_") && !inserted) {
                        updated.put(group); inserted=true;
                    }
                    updated.put(item);
                }
                if(!inserted) updated.put(group);
                layouts.put(l,updated);
            } catch(Exception ignored) {}
        }
        saveLocal();
    }

    private double clamp(double x,double a,double b){return Math.max(a,Math.min(b,x));}
    // Normalizza anche i record già salvati: nessun elemento può restare fuori dal canvas.
    private void constrainLayoutsToCanvas() {
        for (String l : modes) {
            JSONArray a = layouts.get(l);
            for (int i=0;i<a.length();i++) {
                JSONObject o=a.optJSONObject(i); if(o==null) continue;
                double w=clamp(o.optDouble("w",0.02),0.02,1.0);
                double h=clamp(o.optDouble("h",0.02),0.02,1.0);
                double x=clamp(o.optDouble("x",0),0,1.0-w);
                double y=clamp(o.optDouble("y",0),0,1.0-h);
                try { o.put("w",w); o.put("h",h); o.put("x",x); o.put("y",y); } catch(Exception ignored) {}
            }
        }
        saveLocal();
    }
    private void updateStatus(){if(status!=null)status.setText(mode+"  ·  "+current().length()+" elementi  ·  Trascina per spostare, angolo per ridimensionare, X sulle scritte per eliminarle");}
    private void saveLocal(){getPreferences(0).edit().putString("project",toJson().toString()).apply(); if(status!=null)updateStatus();}
    private void loadLocal(){try{JSONObject p=new JSONObject(getPreferences(0).getString("project","{}"));for(String m:modes)if(p.has(m))layouts.put(m,p.getJSONArray(m));}catch(Exception ignored){}}
    private JSONObject toJson(){JSONObject p=new JSONObject();try{p.put("project","FREQUALIZER Layout Designer");p.put("version",1);p.put("devices",new JSONObject().put("tablet","Samsung Galaxy Tab A11+ SM-X230").put("smartphone","Samsung Galaxy S25 base"));for(String m:modes)p.put(m,layouts.get(m));}catch(Exception ignored){}return p;}
    private void export(){saveLocal();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"FREQUALIZER_layout_record.json");startActivityForResult(i,EXPORT);}
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(r==EXPORT&&c==RESULT_OK&&d!=null){try(OutputStream os=getContentResolver().openOutputStream(d.getData())){os.write(toJson().toString(2).getBytes(StandardCharsets.UTF_8));Toast.makeText(this,"Record esportato",Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,"Errore esportazione",Toast.LENGTH_LONG).show();}}}

    private class EditorView extends View {
        Paint p=new Paint(3); int selected=-1; float downX,downY,startX,startY,startW,startH; boolean resizing=false, cancelTap=false;
        JSONArray gestureSnapshot;
        boolean gestureChanged=false;
        final ArrayList<ChildSnapshot> resizeChildren=new ArrayList<>();
        final ArrayList<ChildSnapshot> groupChildren=new ArrayList<>();
        class ChildSnapshot { int index; double x,y,w,h; ChildSnapshot(int i,JSONObject o){index=i;x=o.optDouble("x");y=o.optDouble("y");w=o.optDouble("w");h=o.optDouble("h");} }
        EditorView(){super(MainActivity.this);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        protected void onDraw(Canvas c){super.onDraw(c);c.drawColor(Color.rgb(17,19,23));float pad=18, cw=getWidth()-pad*2,ch=getHeight()-pad*2;float ratio=mode.startsWith("S25") ? (mode.contains("ORIZZONTALE") ? (2340f/1080f) : (1080f/2340f)) : (mode.contains("ORIZZONTALE") ? (1920f/1200f) : (1200f/1920f));float w=cw,h=ch;if(w/h>ratio)w=h*ratio;else h=w/ratio;float left=(getWidth()-w)/2,top=(getHeight()-h)/2;
            p.setColor(Color.rgb(235,235,235));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);c.drawRoundRect(left,top,left+w,top+h,12,12,p);
            p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(17,19,23));c.drawRoundRect(left+2,top+2,left+w-2,top+h-2,10,10,p);
            JSONArray a=current();for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;float x=left+(float)o.optDouble("x")*w,y=top+(float)o.optDouble("y")*h,ww=(float)o.optDouble("w")*w,hh=(float)o.optDouble("h")*h;
                int color;try{color=Color.parseColor(o.optString("color","#DDB833"));}catch(Exception e){color=Color.YELLOW;}
                String type=o.optString("type");p.setColor(color);p.setStyle(type.equals("text")?Paint.Style.FILL:Paint.Style.FILL);if(type.equals("knob")){
                    // Renderer portato dalla RotaryControl originale di FREQUALIZER:
                    // stessa corsa 135°..405°, arco, tacche, centro e indicatore.
                    float reserve=hh*.18f;
                    float side=Math.min(ww,Math.max(1f,hh-reserve))*.94f;
                    float cx=x+ww/2f;
                    float cy=y+(hh-hh*.18f)/2f;
                    float rad=side/2f;
                    RectF arc=new RectF(cx-rad+ww*.025f,cy-rad+ww*.025f,cx+rad-ww*.025f,cy+rad-ww*.025f);
                    p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(2f,rad*.095f));p.setStrokeCap(Paint.Cap.ROUND);
                    p.setColor(Color.rgb(69,75,85));c.drawArc(arc,135f,270f,false,p);
                    for(int tick=0;tick<=20;tick++){
                        double angle=Math.toRadians(135.0+tick*270.0/20.0);
                        float inner=rad*(tick%5==0?.78f:.84f), outer=rad*.98f;
                        p.setColor(tick%5==0?color:Color.rgb(154,162,174));
                        p.setStrokeWidth(Math.max(1f,rad*(tick%5==0?.035f:.018f)));
                        c.drawLine(cx+(float)Math.cos(angle)*inner,cy+(float)Math.sin(angle)*inner,
                                   cx+(float)Math.cos(angle)*outer,cy+(float)Math.sin(angle)*outer,p);
                        if(tick%5==0){p.setStyle(Paint.Style.FILL);c.drawCircle(cx+(float)Math.cos(angle)*rad*.91f,cy+(float)Math.sin(angle)*rad*.91f,Math.max(1f,rad*.035f),p);p.setStyle(Paint.Style.STROKE);}
                    }
                    // I controlli partono neutri: lancetta al centro della corsa.
                    float fraction=.5f;
                    p.setColor(color);p.setStrokeWidth(Math.max(2f,rad*.095f));c.drawArc(arc,135f,270f*fraction,false,p);
                    p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(41,46,54));c.drawCircle(cx,cy,rad*.70f,p);
                    double angle=Math.toRadians(135.0+270.0*fraction);
                    p.setColor(Color.WHITE);p.setStrokeWidth(Math.max(2f,rad*.047f));
                    c.drawLine(cx,cy,cx+(float)Math.cos(angle)*rad*.52f,cy+(float)Math.sin(angle)*rad*.52f,p);
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setColor(Color.WHITE);p.setTextSize(Math.max(6f,Math.min(hh*.11f,ww*.16f)));
                    c.drawText(o.optString("text"),cx,y+hh*.84f,p);
                    p.setColor(color);p.setTextSize(Math.max(5f,Math.min(hh*.095f,ww*.14f)));
                    c.drawText("0.0",cx,y+hh*.98f,p);
                    p.setTextAlign(Paint.Align.LEFT);p.setStyle(Paint.Style.FILL);
                }else if(type.equals("text")){p.setColor(color);p.setTextSize(Math.max(12,hh*.55f));boolean title=o.optString("id").endsWith("_title")||o.optString("id").equals("master_title");p.setTypeface(title?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);c.drawText(o.optString("text"),x,y+hh*.7f,p);p.setTypeface(Typeface.DEFAULT);}else if(type.equals("panel")){/* Layout group: original FREQUALIZER sections have no filled card background. */}else{c.drawRoundRect(x,y,x+ww,y+hh,12,12,p);p.setColor(Color.BLACK);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(Math.max(12,Math.min(ww,hh)*.22f));c.drawText(o.optString("text"),x+ww/2,y+hh/2+p.getTextSize()*.35f,p);p.setTextAlign(Paint.Align.LEFT);}
                if(i==selected){
                    p.setColor(Color.CYAN);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);c.drawRect(x,y,x+ww,y+hh,p);p.setStyle(Paint.Style.FILL);
                    c.drawCircle(x+ww,y+hh,12,p);
                    // La X elimina esclusivamente gli elementi testuali.
                    if("text".equals(type)) {
                        p.setColor(Color.rgb(205,45,50));c.drawCircle(x+ww,y,15,p);
                        p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(19);c.drawText("×",x+ww,y+7,p);p.setTextAlign(Paint.Align.LEFT);
                    }
                }
            }
            p.setColor(Color.LTGRAY);p.setTextSize(22);p.setTextAlign(Paint.Align.CENTER);c.drawText(mode,left+w/2,Math.max(26,top-8),p);p.setTextAlign(Paint.Align.LEFT);
        }
        private float[] stage() {
            float pad=18, cw=getWidth()-pad*2, ch=getHeight()-pad*2;
            float ratio=mode.startsWith("S25") ? (mode.contains("ORIZZONTALE") ? (2340f/1080f) : (1080f/2340f)) : (mode.contains("ORIZZONTALE") ? (1920f/1200f) : (1200f/1920f));
            float w=cw,h=ch;
            if(w/h>ratio) w=h*ratio; else h=w/ratio;
            return new float[]{(getWidth()-w)/2,(getHeight()-h)/2,w,h};
        }
        public boolean onTouchEvent(android.view.MotionEvent e) {
            float x=e.getX(),y=e.getY();
            float[] s=stage(); float left=s[0], top=s[1], sw=s[2], sh=s[3];
            if(e.getAction()==MotionEvent.ACTION_DOWN) {
                downX=x; downY=y; selected=hit(x,y,left,top,sw,sh); resizing=false; cancelTap=false; gestureChanged=false; resizeChildren.clear(); groupChildren.clear();
                try { gestureSnapshot=new JSONArray(current().toString()); } catch(Exception ignored) { gestureSnapshot=null; }
                if(selected>=0) {
                    JSONObject o=current().optJSONObject(selected);
                    float ex=left+(float)o.optDouble("x")*sw, ey=top+(float)o.optDouble("y")*sh;
                    float ew=(float)o.optDouble("w")*sw, eh=(float)o.optDouble("h")*sh;
                    // La X è presente solo sulle scritte e ne permette la cancellazione.
                    cancelTap = "text".equals(o.optString("type")) && x >= ex+ew-30 && x <= ex+ew+8 && y >= ey-12 && y <= ey+28;
                    resizing=!cancelTap && Math.abs(x-(ex+ew))<28 && Math.abs(y-(ey+eh))<28;
                    startX=(float)o.optDouble("x"); startY=(float)o.optDouble("y");
                    startW=(float)o.optDouble("w"); startH=(float)o.optDouble("h");
                    if("panel".equals(o.optString("type"))) {
                        JSONArray all=current();
                        for(int i=0;i<all.length();i++) if(i!=selected) {
                            JSONObject child=all.optJSONObject(i); if(child==null) continue;
                            double cx=child.optDouble("x"), cy=child.optDouble("y"), cw=child.optDouble("w"), ch=child.optDouble("h");
                            boolean delayKeyGroup="delay_keyboard_panel".equals(o.optString("id")) && child.optString("id").startsWith("division_");
                            boolean insidePanel=cx>=startX-0.0001 && cy>=startY-0.0001 && cx+cw<=startX+startW+0.0001 && cy+ch<=startY+startH+0.0001;
                            if(delayKeyGroup || insidePanel) {
                                ChildSnapshot snap=new ChildSnapshot(i,child);
                                resizeChildren.add(snap);
                                groupChildren.add(snap);
                            }
                        }
                    }
                }
                invalidate(); return true;
            }
            if(e.getAction()==MotionEvent.ACTION_MOVE && selected>=0) {
                if(cancelTap) return true;
                if(Math.abs(x-downX)>1f || Math.abs(y-downY)>1f) gestureChanged=true;
                try {
                    JSONObject o=current().getJSONObject(selected);
                    float dx=(x-downX)/sw, dy=(y-downY)/sh;
                    if(resizing) {
                        double newW=clamp(startW+dx*0.35,0.02,1-startX);
                        double newH=clamp(startH+dy*0.35,0.02,1-startY);
                        o.put("w",newW); o.put("h",newH);
                        // La cella scala proporzionalmente tutti i controlli contenuti.
                        if("panel".equals(o.optString("type")) && startW>0 && startH>0) {
                            double sx=newW/startW, sy=newH/startH;
                            for(ChildSnapshot child:resizeChildren) {
                                JSONObject item=current().optJSONObject(child.index); if(item==null) continue;
                                item.put("x",startX+(child.x-startX)*sx);
                                item.put("y",startY+(child.y-startY)*sy);
                                item.put("w",child.w*sx); item.put("h",child.h*sy);
                            }
                        }
                    } else {
                        double actualDx, actualDy;
                        if("panel".equals(o.optString("type")) && !groupChildren.isEmpty()) {
                            double minGX=startX, minGY=startY, maxGX=startX+o.optDouble("w"), maxGY=startY+o.optDouble("h");
                            for(ChildSnapshot child:groupChildren) {
                                minGX=Math.min(minGX,child.x); minGY=Math.min(minGY,child.y);
                                maxGX=Math.max(maxGX,child.x+child.w); maxGY=Math.max(maxGY,child.y+child.h);
                            }
                            actualDx=clamp(dx,-minGX,1-maxGX);
                            actualDy=clamp(dy,-minGY,1-maxGY);
                        } else {
                            double moveX=clamp(startX+dx,0,Math.max(0,1-o.optDouble("w")));
                            double moveY=clamp(startY+dy,0,Math.max(0,1-o.optDouble("h")));
                            actualDx=moveX-startX; actualDy=moveY-startY;
                        }
                        o.put("x",startX+actualDx); o.put("y",startY+actualDy);
                        // Il gruppo DELAY non può mai oltrepassare i bordi del layout.
                        if("panel".equals(o.optString("type"))) {
                            for(ChildSnapshot child:groupChildren) {
                                JSONObject item=current().optJSONObject(child.index); if(item==null) continue;
                                item.put("x",child.x+actualDx);
                                item.put("y",child.y+actualDy);
                            }
                        }
                    }
                    invalidate();
                } catch(Exception ignored) {}
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_UP) {
                if(cancelTap && selected>=0) {
                    rememberUndo();
                    JSONArray old=current(); JSONArray kept=new JSONArray();
                    for(int i=0;i<old.length();i++) if(i!=selected) kept.put(old.opt(i));
                    layouts.put(mode,kept); selected=-1; invalidate(); saveLocal();
                } else {
                    if(gestureChanged) rememberUndo();
                    saveLocal();
                }
                cancelTap=false; gestureSnapshot=null; gestureChanged=false; return true;
            }
            return true;
        }
        private void rememberUndo() {
            if(gestureSnapshot==null) return;
            try { undoSnapshot=new JSONArray(gestureSnapshot.toString()); undoMode=mode; } catch(Exception ignored) {}
        }
        int hit(float x,float y,float left,float top,float sw,float sh) {
            for(int i=current().length()-1;i>=0;i--) {
                JSONObject o=current().optJSONObject(i); if(o==null) continue;
                // I tasti della tastiera DELAY non sono selezionabili singolarmente:
                // l’area completa viene gestita dal solo riquadro delay_keyboard_panel.
                if(o.optString("id").startsWith("division_")) continue;
                float xx=left+(float)o.optDouble("x")*sw, yy=top+(float)o.optDouble("y")*sh;
                float ww=(float)o.optDouble("w")*sw, hh=(float)o.optDouble("h")*sh;
                if(x>=xx-12 && x<=xx+ww+12 && y>=yy-12 && y<=yy+hh+12) return i;
            }
            return -1;
        }
    }
}