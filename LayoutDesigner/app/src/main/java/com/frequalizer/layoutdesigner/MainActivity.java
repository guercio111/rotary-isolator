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
    private static final int MAX_UNDO = 30;
    private final ArrayDeque<UndoEntry> undoHistory = new ArrayDeque<>();
    private static class UndoEntry { final String mode; final JSONArray snapshot; UndoEntry(String m, JSONArray a) { mode=m; snapshot=a; } }

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
        ensureBeatBpmGroup();
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
        addReturnButton(top);
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

    // Undo globale: conserva le ultime 30 modifiche, anche tra profili diversi.
    private void undoLast() {
        if (undoHistory.isEmpty()) {
            Toast.makeText(this, "Nessuna modifica da annullare", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            UndoEntry entry = undoHistory.removeLast();
            layouts.put(entry.mode, new JSONArray(entry.snapshot.toString()));
            if (editor != null) { editor.selected = -1; editor.invalidate(); }
            saveLocal();
            Toast.makeText(this, "Ultima modifica annullata", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }
    private void addReturnButton(LinearLayout row) {
        TextView b = new TextView(this);
        b.setText("↶");
        b.setTextSize(27);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(Color.BLACK);
        b.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(Color.rgb(255,205,0));
        bg.setStroke(2, Color.rgb(255,235,100));
        b.setBackground(bg);
        b.setContentDescription("RETURN - annulla ultima modifica");
        b.setOnClickListener(v -> undoLast());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(48,48);
        lp.leftMargin=8;
        row.addView(b,lp);
    }

    // Preload the real FREQUALIZER control map instead of opening on a blank canvas.
    private void seedLayoutsIfEmpty() {
        // Replace only the original blank-prototype project once. From this point
        // onward the user's edited layouts are always preserved across upgrades.
        if (getPreferences(0).getBoolean("frequalizer_template_seeded_build918", false)) return;
        // Build 918 becomes the new starting reference for all four profiles.
        // This one-time migration intentionally replaces the older prototype maps.
        for (String m : modes) layouts.put(m, new JSONArray());
        seedTabletPortrait(); seedTabletLandscape(); seedPhonePortrait(); seedPhoneLandscape();
        ensureDelayKeyboardGroups();
        constrainLayoutsToCanvas();
        saveLocal();
        getPreferences(0).edit().putBoolean("frequalizer_template_seeded_build918", true).apply();
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
    private void tFader(String l,String id,String title,double x,double y,double w,double h,String color) { templateItem(l,id,"fader",title,x,y,w,h,color); }
    private void seedHeader(String l,boolean compact) {
        tButton(l,"app_power","● ON",.012,.010,.105,.038);
        tButton(l,"bpm_start","BPM START",compact ? .235 : .270,.012,compact ? .18 : .20,.034);
        tText(l,"bpm_readout","— BPM",compact ? .425 : .485,.012,.13,.034);
        tText(l,"bpm_beat","●",compact ? .565 : .655,.004,.045,.045);
        tButton(l,"options","⚙",.915,.008,.065,.042);
    }
    private void seedBandSection(String l,String key,String title,double x,double y,double w,double h,String[] bands,String color,boolean portraitRows) {
        tPanel(l,key+"_panel",title,x,y,w,h);
        tText(l,key+"_title",title,x+.025*w,y+.012*h,.40*w,.075*h);
        tButton(l,key+"_power","ON",x+.025*w,y+.095*h,.14*w,.065*h);
        if(key.equals("ISOLATOR")) tButton(l,"iso_limiter","LIMITER ON",x+.19*w,y+.095*h,.25*w,.065*h);
        tButton(l,key+"_flat","F",x+.84*w,y+.095*h,.10*w,.065*h);
        double head=.18, rowH=(.79)/bands.length;
        for(int i=0;i<bands.length;i++) {
            double ry=y+h*(head+i*rowH);
            if(portraitRows) {
                tText(l,key+"_label_"+i,bands[i],x+.025*w,ry,.23*w,rowH*h*.72);
                tKnob(l,key+"_knob_"+i,bands[i],x+.28*w,ry+.02*rowH*h,.31*w,rowH*h*.76,color);
                tButton(l,key+"_return_"+i,"↶",x+.66*w,ry+.17*rowH*h,.105*w,rowH*h*.54);
                tButton(l,key+"_flat_"+i,"F",x+.79*w,ry+.17*rowH*h,.15*w,rowH*h*.54);
            } else {
                tText(l,key+"_label_"+i,bands[i],x+i*w/bands.length,ry,.95*w/bands.length,.12*h);
                tKnob(l,key+"_knob_"+i,bands[i],x+i*w/bands.length+.05*w/bands.length,ry+.13*h,.88*w/bands.length,.42*h,color);
                tButton(l,key+"_return_"+i,"↶",x+i*w/bands.length+.17*w/bands.length,ry+.59*h,.25*w/bands.length,.12*h);
                tButton(l,key+"_flat_"+i,"F",x+i*w/bands.length+.53*w/bands.length,ry+.59*h,.25*w/bands.length,.12*h);
            }
        }
    }
    private void seedFilterSection(String l,double x,double y,double w,double h,String color,boolean vertical) {
        tPanel(l,"filter_panel","FILTER",x,y,w,h);
        tText(l,"filter_title","FILTER",x+.04*w,y+.012*h,.42*w,.09*h);
        String[] names={"HPF","RESONANCE"};
        for(int i=0;i<2;i++) {
            if(vertical) {
                double ry=y+.12*h+i*.43*h;
                tText(l,"filter_label_"+i,names[i],x+.04*w,ry,.28*w,.16*h);
                tKnob(l,"filter_knob_"+i,names[i],x+.33*w,ry-.015*h,.42*w,.32*h,color);
            } else {
                double cx=x+i*w*.5;
                tText(l,"filter_label_"+i,names[i],cx+.02*w,y+.13*h,.46*w,.12*h);
                tKnob(l,"filter_knob_"+i,names[i],cx+.05*w,y+.25*h,.40*w,.53*h,color);
            }
        }
    }
    private void seedMaster(String l,double x,double y,double w,double h,String color,boolean wide) {
        tPanel(l,"master_panel","MASTER",x,y,w,h);
        tText(l,"master_title","MASTER",x+.025*w,y+.015*h,.45*w,.10*h);
        String[] names={"GAIN","COMP","LOUDNESS"};
        for(int i=0;i<3;i++) {
            if(wide) {
                double ry=y+.12*h+i*.29*h;
                tKnob(l,"master_knob_"+i,names[i],x+.12*w,ry,.72*w,.25*h,color);
                if(i>0) tButton(l,"master_on_"+i,"●",x+.84*w,ry+.07*h,.09*w,.09*h);
            } else {
                double cx=x+i*w/3.0;
                tKnob(l,"master_knob_"+i,names[i],cx+.035*w,y+.20*h,.27*w,.58*h,color);
            }
        }
    }
    private void seedEffects(String l,double x,double y,double w,double h,boolean tabletPortrait,boolean tabletLandscape,boolean phoneWide) {
        tPanel(l,"fx_panel","BEAT FX",x,y,w,h);
        if(tabletLandscape || phoneWide) {
            double revW=w*.25, delayHeaderW=w*.25, controlsW=w*.30, masterW=w*.20;
            tPanel(l,"reverb_panel","REVERB",x,y,revW,h);
            seedReverb(l,x,y,revW,h,true);
            double dx=x+revW;
            tPanel(l,"delay_panel","DELAY",dx,y,delayHeaderW,h);
            tText(l,"delay_title","BEAT EFFECTS",dx+.02*delayHeaderW,y+.02*h,.60*delayHeaderW,.07*h);
            tButton(l,"delay_power","ON",dx+.72*delayHeaderW,y+.02*h,.22*delayHeaderW,.065*h);
            String[] d={"DELAY LEVEL","FEEDBACK","TONE","TIME"};
            for(int i=0;i<4;i++) {
                int col=i%2,row=i/2;
                tKnob(l,"delay_knob_"+i,d[i],dx+col*delayHeaderW*.49,y+.12*h+row*h*.43,delayHeaderW*.46,h*.34,"#46BE78");
            }
            double kx=dx+delayHeaderW;
            tPanel(l,"delay_keyboard_panel","BEAT FX",kx,y,controlsW,h);
            seedDelayKeyboard(l,kx,y,controlsW,h);
            double mx=kx+controlsW;
            seedMaster(l,mx,y,masterW,h,"#46BE78",true);
        } else {
            double revW=w*(tabletPortrait?.37:.43);
            double delayW=w-revW;
            tPanel(l,"reverb_panel","REVERB",x,y,revW,h);
            seedReverb(l,x,y,revW,h,false);
            double dx=x+revW;
            tPanel(l,"delay_panel","DELAY",dx,y,delayW,h);
            tText(l,"delay_title","BEAT EFFECTS",dx+.02*delayW,y+.02*h,.60*delayW,.07*h);
            tButton(l,"delay_power","ON",dx+.72*delayW,y+.02*h,.22*delayW,.065*h);
            String[] d={"DELAY LEVEL","FEEDBACK","TONE","TIME"};
            for(int i=0;i<4;i++) {
                double kx=dx+.02*delayW+i*delayW*.245;
                tKnob(l,"delay_knob_"+i,d[i],kx,y+.10*h,delayW*.22,h*.22,"#46BE78");
            }
            seedDelayKeyboard(l,dx,y+.34*h,delayW,h*.64);
        }
    }
    private void seedReverb(String l,double x,double y,double w,double h,boolean wide) {
        tText(l,"reverb_title","REVERB",x+.025*w,y+.015*h,.48*w,.07*h);
        tButton(l,"reverb_power","ON",x+.58*w,y+.015*h,.18*w,.065*h);
        String[] names={"SEND","TONE","FEEDBACK"};
        tKnob(l,"reverb_0",names[0],x+.04*w,y+.10*h,.42*w,.34*h,"#46BE78");
        tKnob(l,"reverb_1",names[1],x+.53*w,y+.10*h,.42*w,.34*h,"#46BE78");
        tKnob(l,"reverb_2",names[2],x+.29*w,y+.48*h,.42*w,.30*h,"#46BE78");
        String[] bands={"LOW","MID","HIGH"};
        for(int i=0;i<3;i++) tButton(l,"reverb_band_"+i,bands[i],x+.04*w+i*.31*w,y+.82*h,.28*w,.10*h);
    }
    private void seedDelayKeyboard(String l,double x,double y,double w,double h) {
        // Build 918 order: TAP/ALIGN and beat lamps, clock buttons, PITCH,
        // FX phase jog, effect mode, beat divisions and frequency selectors.
        tButton(l,"delay_tap","TAP",x+.015*w,y+.012*h,.105*w,.105*h);
        tButton(l,"delay_align","ALIGN",x+.135*w,y+.012*h,.105*w,.105*h);
        templateItem(l,"delay_master_beat","text","●",x+.265*w,y+.012*h,.075*w,.105*h,"#46BE78");
        templateItem(l,"delay_fx_beat","text","●",x+.365*w,y+.012*h,.075*w,.105*h,"#46BE78");
        templateItem(l,"delay_bpm","text","120.0 BPM",x+.485*w,y+.012*h,.49*w,.105*h,"#FFFFFF");
        String[] clock={"AUTO","LOCK","◀","▶","SKIP","RST"};
        String[] ids={"delay_auto","delay_lock","delay_back","delay_forward","delay_skip","delay_rst"};
        for(int i=0;i<clock.length;i++) templateItem(l,ids[i],"button",clock[i],x+.012*w+i*.165*w,y+.145*h,.155*w,.092*h,"#46BE78");
        tText(l,"delay_pitch_label","PITCH  −10%  |  0  |  +10%",x+.02*w,y+.255*h,.96*w,.055*h);
        tFader(l,"delay_pitch_fader","PITCH",x+.025*w,y+.315*h,.95*w,.052*h,"#46BE78");
        tText(l,"delay_phase_label","FX PHASE JOG  •  MISURA…",x+.02*w,y+.382*h,.96*w,.055*h);
        tFader(l,"delay_phase_fader","FX PHASE JOG",x+.025*w,y+.442*h,.95*w,.052*h,"#46BE78");
        String[] modes={"SHORT DELAY","LONG DELAY","DUB ECHO"};
        for(int i=0;i<3;i++) templateItem(l,"delay_mode_"+i,"button",modes[i],x+.015*w+i*.325*w,y+.515*h,.31*w,.105*h,"#46BE78");
        String[] divisions={"1/16","1/8","1/4","1/2","3/4","1","2","4","8","16"};
        for(int i=0;i<divisions.length;i++) templateItem(l,"division_"+i,"button",divisions[i],x+.012*w+i*.098*w,y+.642*h,.09*w,.115*h,"#46BE78");
        String[] fx={"FX LOW","FX MID","FX HIGH"};
        for(int i=0;i<3;i++) templateItem(l,"fx_"+new String[]{"low","mid","high"}[i],"button",fx[i],x+.015*w+i*.325*w,y+.785*h,.31*w,.105*h,"#46BE78");
    }
    private void seedTabletPortrait() {
        String l=modes[0]; seedHeader(l,false);
        seedBandSection(l,"EQ","EQ",.012,.065,.478,.305,new String[]{"HIGH","MID HIGH","MID LOW","LOW"},"#46BE78",true);
        seedBandSection(l,"ISOLATOR","ISOLATOR",.510,.065,.478,.305,new String[]{"HIGH","MID","LOW"},"#46BE78",true);
        seedFilterSection(l,.012,.382,.478,.326,"#46BE78",true);
        seedMaster(l,.510,.382,.478,.326,"#46BE78",false);
        seedEffects(l,.012,.718,.976,.274,true,false,false);
    }
    private void seedTabletLandscape() {
        String l=modes[1]; seedHeader(l,false);
        seedBandSection(l,"EQ","EQ",.012,.065,.478,.425,new String[]{"HIGH","MID HIGH","MID LOW","LOW"},"#46BE78",false);
        seedBandSection(l,"ISOLATOR","ISOLATOR",.510,.065,.478,.425,new String[]{"HIGH","MID","LOW"},"#46BE78",false);
        seedFilterSection(l,.012,.505,.19,.475,"#46BE78",true);
        seedEffects(l,.205,.505,.783,.475,false,true,false);
    }
    private void seedPhonePortrait() {
        String l=modes[2]; seedHeader(l,true);
        seedBandSection(l,"EQ","EQ",.012,.065,.478,.425,new String[]{"HIGH","MID HIGH","MID LOW","LOW"},"#46BE78",false);
        seedBandSection(l,"ISOLATOR","ISOLATOR",.510,.065,.478,.425,new String[]{"HIGH","MID","LOW"},"#46BE78",false);
        seedFilterSection(l,.012,.505,.235,.475,"#46BE78",false);
        seedMaster(l,.255,.505,.235,.475,"#46BE78",false);
        seedEffects(l,.500,.505,.488,.475,false,false,false);
    }
    private void seedPhoneLandscape() {
        String l=modes[3]; seedHeader(l,true);
        seedBandSection(l,"EQ","EQ",.012,.065,.478,.425,new String[]{"HIGH","MID HIGH","MID LOW","LOW"},"#46BE78",false);
        seedBandSection(l,"ISOLATOR","ISOLATOR",.510,.065,.478,.425,new String[]{"HIGH","MID","LOW"},"#46BE78",false);
        seedFilterSection(l,.012,.505,.235,.475,"#46BE78",false);
        seedMaster(l,.255,.505,.235,.475,"#46BE78",false);
        seedEffects(l,.500,.505,.488,.475,false,false,false);
    }

    // La tastiera delle divisioni DELAY è un unico gruppo indivisibile:
    // tasti e controlli contenuti si muovono e si ridimensionano insieme.
    private boolean isDelayKeyboardChild(String id) {
        return id.startsWith("division_") || id.equals("delay_bpm") ||
            id.equals("delay_tap") || id.equals("delay_auto") || id.equals("delay_lock") ||
            id.equals("delay_align") || id.equals("delay_back") || id.equals("delay_forward") ||
            id.equals("delay_skip") || id.equals("delay_rst") || id.equals("delay_phase_label") ||
            id.equals("delay_phase_fader") || id.startsWith("delay_mode_") ||
            id.equals("fx_low") || id.equals("fx_mid") || id.equals("fx_high");
    }
    // Tastiera BEAT FX indivisibile: tasti, divisioni, fader e selettori restano un gruppo.
    private void ensureDelayKeyboardGroups() {
        for (String l : modes) {
            JSONArray old=layouts.get(l);
            double minX=1,minY=1,maxX=0,maxY=0;
            for(int i=0;i<old.length();i++) {
                JSONObject item=old.optJSONObject(i);
                if(item==null || !isDelayKeyboardChild(item.optString("id"))) continue;
                double x=item.optDouble("x"),y=item.optDouble("y"),w=item.optDouble("w"),h=item.optDouble("h");
                minX=Math.min(minX,x); minY=Math.min(minY,y);
                maxX=Math.max(maxX,x+w); maxY=Math.max(maxY,y+h);
            }
            int groupIndex=-1;
            for(int i=0;i<old.length();i++) {
                JSONObject o=old.optJSONObject(i);
                if(o!=null && "delay_keyboard_panel".equals(o.optString("id"))) {groupIndex=i;break;}
            }
            // Se il gruppo esiste già, conserva anche il suo perimetro originale:
            // non restringerlo ai soli controlli riconosciuti, altrimenti gli indicatori
            // BPM vicini ai bordi possono restare fuori quando si sposta il gruppo.
            if(groupIndex>=0) {
                JSONObject existing=old.optJSONObject(groupIndex);
                if(existing!=null) {
                    double gx=existing.optDouble("x"), gy=existing.optDouble("y");
                    double gw=existing.optDouble("w"), gh=existing.optDouble("h");
                    minX=Math.min(minX,gx); minY=Math.min(minY,gy);
                    maxX=Math.max(maxX,gx+gw); maxY=Math.max(maxY,gy+gh);
                }
            }
            if(maxX<=minX || maxY<=minY) continue;
            try {
                JSONObject group=groupIndex>=0?old.getJSONObject(groupIndex):new JSONObject();
                group.put("id","delay_keyboard_panel"); group.put("type","panel"); group.put("text","");
                group.put("x",Math.max(0,minX-.008)); group.put("y",Math.max(0,minY-.012));
                group.put("w",Math.min(1,maxX+.008)-Math.max(0,minX-.008));
                group.put("h",Math.min(1,maxY+.012)-Math.max(0,minY-.012)); group.put("color","#252A31");
                if(groupIndex<0) old.put(0,group);
            } catch(Exception ignored) {}
        }
        saveLocal();
    }

    // Unico gruppo BPM/BEAT: header, indicatori, TAP/AUTO, frecce,
    // fader, divisioni, modalità delay ed FX restano solidali.
    private boolean isBpmHeaderChild(String id) { return id.startsWith("bpm_"); }
    private boolean isBeatBpmChild(String id) {
        return isBpmHeaderChild(id) || isDelayKeyboardChild(id);
    }
    private void ensureBeatBpmGroup() {
        for (String l : modes) {
            JSONArray original=layouts.get(l);
            JSONArray items=new JSONArray();
            JSONObject existingGroup=null;
            // Rimuove i vecchi contenitori separati, senza toccare le coordinate
            // dei controlli: ora esiste un solo gruppo comune.
            for(int i=0;i<original.length();i++) {
                JSONObject item=original.optJSONObject(i);
                if(item==null) continue;
                String id=item.optString("id");
                if("beat_bpm_section_panel".equals(id)) { existingGroup=item; continue; }
                if("delay_keyboard_panel".equals(id) || "bpm_header_panel".equals(id)) continue;
                items.put(item);
            }
            double minX=1,minY=1,maxX=0,maxY=0;
            for(int i=0;i<items.length();i++) {
                JSONObject item=items.optJSONObject(i);
                if(item==null || !isBeatBpmChild(item.optString("id"))) continue;
                double x=item.optDouble("x"),y=item.optDouble("y"),w=item.optDouble("w"),h=item.optDouble("h");
                minX=Math.min(minX,x); minY=Math.min(minY,y);
                maxX=Math.max(maxX,x+w); maxY=Math.max(maxY,y+h);
            }
            if(maxX<=minX || maxY<=minY) { layouts.put(l,items); continue; }
            if(existingGroup!=null) {
                double gx=existingGroup.optDouble("x"),gy=existingGroup.optDouble("y");
                double gw=existingGroup.optDouble("w"),gh=existingGroup.optDouble("h");
                minX=Math.min(minX,gx); minY=Math.min(minY,gy);
                maxX=Math.max(maxX,gx+gw); maxY=Math.max(maxY,gy+gh);
            }
            try {
                JSONObject group=existingGroup==null?new JSONObject():existingGroup;
                group.put("id","beat_bpm_section_panel"); group.put("type","panel"); group.put("text","");
                group.put("x",Math.max(0,minX-.008)); group.put("y",Math.max(0,minY-.012));
                group.put("w",Math.min(1,maxX+.008)-Math.max(0,minX-.008));
                group.put("h",Math.min(1,maxY+.012)-Math.max(0,minY-.012)); group.put("color","#252A31");
                items.put(0,group);
            } catch(Exception ignored) {}
            layouts.put(l,items);
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
    private void updateStatus(){if(status!=null)status.setText(mode+"  ·  Tocca un elemento per selezionarlo  ·  Spazio vuoto: pannello  ·  Trascina per spostare, angolo per ridimensionare");}
    private void saveLocal(){getPreferences(0).edit().putString("project",toJson().toString()).apply(); if(status!=null)updateStatus();}
    private void loadLocal(){try{JSONObject p=new JSONObject(getPreferences(0).getString("project","{}"));for(String m:modes)if(p.has(m))layouts.put(m,p.getJSONArray(m));}catch(Exception ignored){}}
    private JSONObject toJson(){JSONObject p=new JSONObject();try{p.put("project","FREQUALIZER Layout Designer");p.put("version",1);p.put("devices",new JSONObject().put("tablet","Samsung Galaxy Tab A11+ SM-X230").put("smartphone","Samsung Galaxy S25 base"));for(String m:modes)p.put(m,layouts.get(m));}catch(Exception ignored){}return p;}
    private void export(){saveLocal();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"FREQUALIZER_layout_record.json");startActivityForResult(i,EXPORT);}
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(r==EXPORT&&c==RESULT_OK&&d!=null){try(OutputStream os=getContentResolver().openOutputStream(d.getData())){os.write(toJson().toString(2).getBytes(StandardCharsets.UTF_8));Toast.makeText(this,"Record esportato",Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,"Errore esportazione",Toast.LENGTH_LONG).show();}}}

    private class EditorView extends View {
        Paint p=new Paint(3); int selected=-1; float downX,downY,startX,startY,startW,startH; boolean resizing=false, cancelTap=false; boolean editUnlocked=false, longPressTriggered=false, deleteOnUp=false; final android.os.Handler editHandler=new android.os.Handler(android.os.Looper.getMainLooper()); Runnable longPressRunnable;
        long lastTapTime=0; float lastTapX=0,lastTapY=0; int tapPanel=-1; boolean doubleTap=false;
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
                    // Port fedele del disegno RotaryControl originale:
                    // stessi margini dp, dimensionamento del quadrante, tacche e testi.
                    float refWidthDp=mode.startsWith("S25")?(mode.contains("ORIZZONTALE")?852f:393f):(mode.contains("ORIZZONTALE")?1280f:800f);
                    float layoutScale=1f;
                    if(mode.startsWith("S25")&&mode.contains("ORIZZONTALE")) layoutScale=Math.max(.42f,Math.min(1f,Math.min(852f/1280f,393f/800f)));
                    float dp=(w/refWidthDp)*layoutScale;
                    String id=o.optString("id");
                    boolean portraitFit=!mode.contains("ORIZZONTALE")&&(id.startsWith("eq_knob_")||id.startsWith("iso_knob_")||id.startsWith("filter_knob_"));
                    float textReserve=dp*(portraitFit?27f:18f);
                    float side=Math.min(ww,Math.max(1f,hh-textReserve))*.94f;
                    float cy=y+(portraitFit?(hh-dp*27f)/2f:(hh-dp*26f)/2f);
                    float cx=x+ww/2f;
                    float rad=side/2f;
                    RectF arc=new RectF(cx-rad+dp*5f,cy-rad+dp*5f,cx+rad-dp*5f,cy+rad-dp*5f);
                    p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp*6f);p.setStrokeCap(Paint.Cap.ROUND);
                    p.setColor(Color.rgb(69,75,85));c.drawArc(arc,135f,270f,false,p);
                    for(int tick=0;tick<=20;tick++){
                        double angle=Math.toRadians(135.0+tick*270.0/20.0);
                        float inner=rad*(tick%5==0?.78f:.84f),outer=rad*.98f;
                        p.setColor(tick%5==0?color:Color.rgb(154,162,174));
                        p.setStrokeWidth(dp*(tick%5==0?2.5f:1.3f));
                        c.drawLine(cx+(float)Math.cos(angle)*inner,cy+(float)Math.sin(angle)*inner,cx+(float)Math.cos(angle)*outer,cy+(float)Math.sin(angle)*outer,p);
                        if(tick%5==0){p.setStyle(Paint.Style.FILL);c.drawCircle(cx+(float)Math.cos(angle)*rad*.91f,cy+(float)Math.sin(angle)*rad*.91f,dp*3.2f,p);p.setStyle(Paint.Style.STROKE);}
                    }
                    float fraction=.5f;
                    if(id.startsWith("filter_knob_")||id.startsWith("reverb_")||id.startsWith("delay_knob_")) fraction=0f;
                    if(id.equals("master_knob_1")||id.equals("master_knob_2")||id.equals("master_knob_3")) fraction=0f;
                    p.setColor(color);p.setStrokeWidth(dp*6f);c.drawArc(arc,135f,270f*fraction,false,p);
                    p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(41,46,54));c.drawCircle(cx,cy,rad*.70f,p);
                    double angle=Math.toRadians(135.0+270.0*fraction);
                    p.setColor(Color.WHITE);p.setStrokeWidth(dp*3f);
                    c.drawLine(cx,cy,cx+(float)Math.cos(angle)*rad*.52f,cy+(float)Math.sin(angle)*rad*.52f,p);
                    p.setTextAlign(Paint.Align.CENTER);
                    String dialTitle=o.optString("text");
                    float titleSize=Math.min(dp*11f,rad*2f*.86f/(Math.max(1,dialTitle.length())*.58f));
                    p.setTextSize(Math.max(dp*5.5f,titleSize));p.setColor(Color.WHITE);
                    c.drawText(dialTitle,cx,y+hh-dp*17f,p);
                    String valueText=(id.startsWith("eq_knob_")||id.startsWith("iso_knob_")||id.equals("master_knob_0"))?"0.0 dB":"0%";
                    float valueSize=Math.min(dp*10f,rad*2f*.86f/(Math.max(1,valueText.length())*.58f));
                    p.setColor(color);p.setTextSize(Math.max(dp*5.5f,valueSize));
                    c.drawText(valueText,cx,y+hh-dp*4f,p);
                    p.setTextAlign(Paint.Align.LEFT);p.setStyle(Paint.Style.FILL);
                }else if(type.equals("text")){p.setColor(color);p.setTextSize(Math.max(12,hh*.55f));boolean title=o.optString("id").endsWith("_title")||o.optString("id").equals("master_title");p.setTypeface(title?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);c.drawText(o.optString("text"),x,y+hh*.7f,p);p.setTypeface(Typeface.DEFAULT);}else if(type.equals("fader")){float cy=y+hh/2f;p.setColor(Color.rgb(75,85,95));p.setStrokeWidth(Math.max(2f,hh*.12f));p.setStrokeCap(Paint.Cap.ROUND);c.drawLine(x+ww*.06f,cy,x+ww*.94f,cy,p);p.setColor(color);p.setStrokeWidth(Math.max(2f,hh*.16f));c.drawLine(x+ww*.06f,cy,x+ww*.50f,cy,p);p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);c.drawCircle(x+ww*.50f,cy,Math.max(3f,hh*.34f),p);}else if(type.equals("panel")){/* Layout group: original FREQUALIZER sections have no filled card background. */}else{
                    String id=o.optString("id"),label=o.optString("text");
                    p.setStyle(Paint.Style.FILL);
                    if(id.equals("options")){
                        float outer=Math.min(ww,hh)*.43f,root=outer*.76f,cx=x+ww/2f,cy=y+hh/2f;
                        Path path=new Path();
                        for(int n=0;n<32;n++){float radius=(n%4==0||n%4==1)?outer:root;double gearAngle=Math.toRadians(-90.0+n*360.0/32.0);float px=cx+(float)Math.cos(gearAngle)*radius,py=cy+(float)Math.sin(gearAngle)*radius;if(n==0)path.moveTo(px,py);else path.lineTo(px,py);}
                        path.close();p.setColor(Color.rgb(255,205,0));c.drawPath(path,p);p.setColor(Color.rgb(17,19,23));c.drawCircle(cx,cy,outer*.37f,p);p.setColor(Color.rgb(255,205,0));c.drawCircle(cx,cy,outer*.17f,p);
                    }else if(id.endsWith("_flat")||id.contains("_return_")||id.startsWith("master_return_")){
                        float radius=Math.min(ww,hh)*.47f;p.setColor(Color.rgb(255,205,0));c.drawCircle(x+ww/2f,y+hh/2f,radius,p);p.setColor(Color.rgb(255,235,100));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(1f,radius*.07f));c.drawCircle(x+ww/2f,y+hh/2f,radius,p);p.setStyle(Paint.Style.FILL);p.setColor(Color.BLACK);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(Math.max(8f,Math.min(ww,hh)*.42f));c.drawText(label,x+ww/2f,y+hh/2f+p.getTextSize()*.35f,p);p.setTextAlign(Paint.Align.LEFT);
                    }else{
                        boolean toggle=id.equals("app_power")||id.equals("bpm_start")||id.endsWith("_power")||id.startsWith("master_on_");
                        boolean off=id.equals("reverb_power")||id.equals("delay_power")||id.startsWith("master_on_")||id.startsWith("fx_");
                        boolean beatControl=id.startsWith("delay_")||id.startsWith("division_")||id.startsWith("fx_")||id.startsWith("reverb_band_");
                        if(toggle || beatControl){
                            int fill=off||beatControl?Color.rgb(55,62,72):Color.rgb(25,150,65);
                            int stroke=off||beatControl?color:Color.rgb(100,230,130);
                            p.setColor(fill);c.drawRoundRect(x,y,x+ww,y+hh,Math.min(ww,hh)*.22f,Math.min(ww,hh)*.22f,p);
                            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(1f,ww*.012f));p.setColor(stroke);c.drawRoundRect(x,y,x+ww,y+hh,Math.min(ww,hh)*.22f,Math.min(ww,hh)*.22f,p);p.setStyle(Paint.Style.FILL);
                            p.setColor(Color.WHITE);
                        }else{
                            p.setColor(color);c.drawRoundRect(x,y,x+ww,y+hh,Math.min(ww,hh)*.22f,Math.min(ww,hh)*.22f,p);
                            p.setColor(Color.BLACK);
                        }
                        p.setTextAlign(Paint.Align.CENTER);p.setTextSize(Math.max(8f,Math.min(ww,hh)*.28f));p.setTypeface(Typeface.DEFAULT_BOLD);c.drawText(label,x+ww/2f,y+hh/2f+p.getTextSize()*.35f,p);p.setTypeface(Typeface.DEFAULT);p.setTextAlign(Paint.Align.LEFT);
                    }
                }
                if(i==selected){
                    p.setColor(editUnlocked?Color.rgb(255,205,0):Color.CYAN);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);c.drawRect(x,y,x+ww,y+hh,p);p.setStyle(Paint.Style.FILL);
                    if(editUnlocked){
                        // Delete and resize handles are available for every element type.
                        p.setColor(Color.rgb(205,45,50));c.drawCircle(x+ww,y,15,p);
                        p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(19);c.drawText("×",x+ww,y+7,p);p.setTextAlign(Paint.Align.LEFT);
                        p.setColor(Color.rgb(255,205,0));c.drawCircle(x+ww,y+hh,12,p);
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
            float[] stage=stage(); float left=stage[0], top=stage[1], sw=stage[2], sh=stage[3];
            if(e.getAction()==MotionEvent.ACTION_DOWN) {
                editHandler.removeCallbacks(longPressRunnable);
                downX=x; downY=y; longPressTriggered=false; deleteOnUp=false;
                int deleteHit=editUnlocked?hitDeleteHandle(x,y,left,top,sw,sh):-1;
                if(deleteHit>=0) {
                    deleteOnUp=true; gestureChanged=false;
                    try { gestureSnapshot=new JSONArray(current().toString()); } catch(Exception ignored) { gestureSnapshot=null; }
                    invalidate(); return true;
                }
                int elementHit=hitElement(x,y,left,top,sw,sh,-1);
                tapPanel=elementHit>=0?-1:hitPanel(x,y,left,top,sw,sh);
                selected=elementHit>=0?elementHit:tapPanel;
                editUnlocked=false; resizing=false; cancelTap=false; gestureChanged=false;
                resizeChildren.clear(); groupChildren.clear();
                try { gestureSnapshot=new JSONArray(current().toString()); } catch(Exception ignored) { gestureSnapshot=null; }
                if(selected>=0) {
                    JSONObject target=current().optJSONObject(selected);
                    startX=(float)target.optDouble("x"); startY=(float)target.optDouble("y");
                    startW=(float)target.optDouble("w"); startH=(float)target.optDouble("h");
                    longPressRunnable=()->{
                        if(selected<0) return;
                        longPressTriggered=true; editUnlocked=true;
                        float ex=left+startX*sw, ey=top+startY*sh, ew=startW*sw, eh=startH*sh;
                        float brx=x, bry=y;
                        resizing=Math.hypot(brx-(ex+ew),bry-(ey+eh))<=30f;
                        JSONObject selectedObject=current().optJSONObject(selected);
                        if(selectedObject!=null && "panel".equals(selectedObject.optString("type"))) {
                            JSONArray all=current();
                            for(int i=0;i<all.length();i++) if(i!=selected) {
                                JSONObject child=all.optJSONObject(i); if(child==null) continue;
                                double cx=child.optDouble("x"),cy=child.optDouble("y"),cw=child.optDouble("w"),ch=child.optDouble("h");
                                boolean beatGroup="beat_bpm_section_panel".equals(selectedObject.optString("id")) && isBeatBpmChild(child.optString("id"));
                                boolean inside=!"beat_bpm_section_panel".equals(selectedObject.optString("id")) &&
                                    cx>=startX-0.0001 && cy>=startY-0.0001 && cx+cw<=startX+startW+0.0001 && cy+ch<=startY+startH+0.0001;
                                if(beatGroup||inside) { ChildSnapshot snap=new ChildSnapshot(i,child); resizeChildren.add(snap); groupChildren.add(snap); }
                            }
                        }
                        invalidate();
                        Toast.makeText(MainActivity.this,resizing?"RIDIMENSIONA":"MODIFICA ATTIVA",Toast.LENGTH_SHORT).show();
                    };
                    editHandler.postDelayed(longPressRunnable,550L);
                } else {
                    longPressRunnable=null;
                }
                invalidate(); return true;
            }
            if(e.getAction()==MotionEvent.ACTION_MOVE) {
                if(!longPressTriggered || !editUnlocked || selected<0 || deleteOnUp) return true;
                if(Math.abs(x-downX)>2f || Math.abs(y-downY)>2f) gestureChanged=true;
                try {
                    JSONObject item=current().getJSONObject(selected);
                    double dx=(x-downX)/sw, dy=(y-downY)/sh;
                    if(resizing) {
                        double newW=clamp(snapToGrid(startW+dx),0.02,1-startX);
                        double newH=clamp(snapToGrid(startH+dy),0.02,1-startY);
                        item.put("w",newW); item.put("h",newH);
                        if("panel".equals(item.optString("type")) && startW>0 && startH>0) {
                            double sx=newW/startW, sy=newH/startH;
                            for(ChildSnapshot child:resizeChildren) {
                                JSONObject member=current().optJSONObject(child.index); if(member==null) continue;
                                member.put("x",snapToGrid(startX+(child.x-startX)*sx));
                                member.put("y",snapToGrid(startY+(child.y-startY)*sy));
                                member.put("w",Math.max(0.01,snapToGrid(child.w*sx)));
                                member.put("h",Math.max(0.01,snapToGrid(child.h*sy)));
                            }
                        }
                    } else {
                        double targetX=snapToGrid(startX+dx), targetY=snapToGrid(startY+dy);
                        double minX=startX,minY=startY,maxX=startX+startW,maxY=startY+startH;
                        for(ChildSnapshot child:groupChildren) {
                            minX=Math.min(minX,child.x); minY=Math.min(minY,child.y);
                            maxX=Math.max(maxX,child.x+child.w); maxY=Math.max(maxY,child.y+child.h);
                        }
                        double actualDx=clamp(targetX-startX,-minX,1-maxX);
                        double actualDy=clamp(targetY-startY,-minY,1-maxY);
                        item.put("x",snapToGrid(startX+actualDx)); item.put("y",snapToGrid(startY+actualDy));
                        for(ChildSnapshot child:groupChildren) {
                            JSONObject member=current().optJSONObject(child.index); if(member==null) continue;
                            member.put("x",snapToGrid(child.x+actualDx));
                            member.put("y",snapToGrid(child.y+actualDy));
                        }
                    }
                    invalidate();
                } catch(Exception ignored) {}
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_UP) {
                editHandler.removeCallbacks(longPressRunnable);
                if(deleteOnUp && selected>=0) {
                    rememberUndo();
                    JSONArray old=current(), kept=new JSONArray();
                    JSONObject victim=old.optJSONObject(selected);
                    boolean panel=victim!=null && "panel".equals(victim.optString("type"));
                    double vx=panel?victim.optDouble("x"):0, vy=panel?victim.optDouble("y"):0;
                    double vw=panel?victim.optDouble("w"):0, vh=panel?victim.optDouble("h"):0;
                    for(int i=0;i<old.length();i++) {
                        JSONObject candidate=old.optJSONObject(i);
                        if(i==selected) continue;
                        if(panel && candidate!=null && candidate.optDouble("x")>=vx-0.0001 && candidate.optDouble("y")>=vy-0.0001 &&
                            candidate.optDouble("x")+candidate.optDouble("w")<=vx+vw+0.0001 &&
                            candidate.optDouble("y")+candidate.optDouble("h")<=vy+vh+0.0001) continue;
                        kept.put(old.opt(i));
                    }
                    layouts.put(mode,kept); selected=-1; editUnlocked=false; invalidate(); saveLocal();
                    Toast.makeText(MainActivity.this,"Elemento eliminato",Toast.LENGTH_SHORT).show();
                } else {
                    if(longPressTriggered && gestureChanged) rememberUndo();
                    if(longPressTriggered) saveLocal();
                }
                deleteOnUp=false; longPressTriggered=false; gestureSnapshot=null; gestureChanged=false;
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_CANCEL) {
                editHandler.removeCallbacks(longPressRunnable);
                deleteOnUp=false; longPressTriggered=false; gestureSnapshot=null; gestureChanged=false;
                return true;
            }
            return true;
        }
        private void rememberUndo() {
            if(gestureSnapshot==null) return;
            try {
                if(undoHistory.size()>=MAX_UNDO) undoHistory.removeFirst();
                undoHistory.addLast(new UndoEntry(mode,new JSONArray(gestureSnapshot.toString())));
            } catch(Exception ignored) {}
        }
        private double snapToGrid(double value){return Math.round(value*100.0)/100.0;}
        // La X rossa viene verificata prima di qualsiasi controllo o pannello.
        // La zona sensibile è più ampia del cerchio visibile per facilitare il tocco.
        boolean hitResizeHandle(float x,float y,float left,float top,float sw,float sh) {
            if(selected<0) return false;
            JSONObject o=current().optJSONObject(selected);
            if(o==null || "panel".equals(o.optString("type"))) return false;
            float ex=left+(float)o.optDouble("x")*sw;
            float ey=top+(float)o.optDouble("y")*sh;
            float ew=(float)o.optDouble("w")*sw;
            float eh=(float)o.optDouble("h")*sh;
            float dx=x-(ex+ew),dy=y-(ey+eh);
            return dx*dx+dy*dy<=22f*22f;
        }
        int hitDeleteHandle(float x,float y,float left,float top,float sw,float sh) {
            if(selected<0 || !editUnlocked) return -1;
            JSONObject o=current().optJSONObject(selected);
            if(o==null) return -1;
            float ex=left+(float)o.optDouble("x")*sw;
            float ey=top+(float)o.optDouble("y")*sh;
            float ew=(float)o.optDouble("w")*sw;
            float dx=x-(ex+ew), dy=y-ey;
            return dx*dx+dy*dy<=30f*30f?selected:-1;
        }
        // I controlli hanno priorità sui pannelli: il pannello si seleziona
        // soltanto toccando una zona libera al suo interno.
        int hitPanel(float x,float y,float left,float top,float sw,float sh) {
            // Il tocco su qualsiasi controllo BPM/BEAT seleziona sempre il gruppo completo.
            for(int i=0;i<current().length();i++) {
                JSONObject panel=current().optJSONObject(i);
                if(panel==null || !"beat_bpm_section_panel".equals(panel.optString("id"))) continue;
                for(int j=0;j<current().length();j++) {
                    JSONObject child=current().optJSONObject(j);
                    if(child==null || !isBeatBpmChild(child.optString("id"))) continue;
                    float cx=left+(float)child.optDouble("x")*sw, cy=top+(float)child.optDouble("y")*sh;
                    float cw=(float)child.optDouble("w")*sw, ch=(float)child.optDouble("h")*sh;
                    if(x>=cx-5 && x<=cx+cw+5 && y>=cy-5 && y<=cy+ch+5) return i;
                }
            }
            int best=-1; double bestArea=Double.MAX_VALUE;
            for(int i=0;i<current().length();i++) {
                JSONObject o=current().optJSONObject(i);
                if(o==null || !"panel".equals(o.optString("type"))) continue;
                float xx=left+(float)o.optDouble("x")*sw, yy=top+(float)o.optDouble("y")*sh;
                float ww=(float)o.optDouble("w")*sw, hh=(float)o.optDouble("h")*sh;
                if(x>=xx-8 && x<=xx+ww+8 && y>=yy-8 && y<=yy+hh+8) {
                    double area=ww*hh;
                    if(area<bestArea) { best=i; bestArea=area; }
                }
            }
            return best;
        }
        boolean isInsideDelayKeyboardGroup(JSONObject item) {
            double x=item.optDouble("x"), y=item.optDouble("y");
            double w=item.optDouble("w"), h=item.optDouble("h");
            for(int i=0;i<current().length();i++) {
                JSONObject panel=current().optJSONObject(i);
                if(panel==null || !"delay_keyboard_panel".equals(panel.optString("id"))) continue;
                double px=panel.optDouble("x"), py=panel.optDouble("y");
                double pw=panel.optDouble("w"), ph=panel.optDouble("h");
                if(x>=px-0.0001 && y>=py-0.0001 && x+w<=px+pw+0.0001 && y+h<=py+ph+0.0001) return true;
            }
            return false;
        }
        boolean isInsideBpmHeaderGroup(JSONObject item) {
            double x=item.optDouble("x"), y=item.optDouble("y");
            double w=item.optDouble("w"), h=item.optDouble("h");
            for(int i=0;i<current().length();i++) {
                JSONObject panel=current().optJSONObject(i);
                if(panel==null || !"bpm_header_panel".equals(panel.optString("id"))) continue;
                double px=panel.optDouble("x"), py=panel.optDouble("y");
                double pw=panel.optDouble("w"), ph=panel.optDouble("h");
                if(x>=px-0.0001 && y>=py-0.0001 && x+w<=px+pw+0.0001 && y+h<=py+ph+0.0001) return true;
            }
            return false;
        }
        int hitElement(float x,float y,float left,float top,float sw,float sh,int requiredPanel) {
            for(int i=current().length()-1;i>=0;i--) {
                JSONObject o=current().optJSONObject(i); if(o==null) continue;
                String id=o.optString("id"), type=o.optString("type");
                // La sezione BEAT/DELAY è indivisibile: i suoi elementi non si selezionano singolarmente.
                if("panel".equals(type) || isBeatBpmChild(id)) continue;
                if(requiredPanel>=0) {
                    JSONObject panel=current().optJSONObject(requiredPanel);
                    if(panel==null) continue;
                    double px=panel.optDouble("x"), py=panel.optDouble("y");
                    double pw=panel.optDouble("w"), ph=panel.optDouble("h");
                    double ex=o.optDouble("x"), ey=o.optDouble("y");
                    if(ex<px-0.0001 || ey<py-0.0001 || ex+o.optDouble("w")>px+pw+0.0001 || ey+o.optDouble("h")>py+ph+0.0001) continue;
                }
                float xx=left+(float)o.optDouble("x")*sw, yy=top+(float)o.optDouble("y")*sh;
                float ww=(float)o.optDouble("w")*sw, hh=(float)o.optDouble("h")*sh;
                // Tutta la superficie dell'elemento è selezionabile, anche se piccola.
                if(x>=xx-4 && x<=xx+ww+4 && y>=yy-4 && y<=yy+hh+4) return i;
            }
            return -1;
        }
    }
}