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

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        for (String m : modes) layouts.put(m, new JSONArray());
        loadLocal();
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
        addTopButton(top, "TESTO", () -> add("text"));
        addTopButton(top, "PANNELLO", () -> add("panel"));
        addTopButton(top, "TASTO", () -> add("button"));
        addTopButton(top, "MANOPOLA", () -> add("knob"));
        root.addView(top);
        editor = new EditorView();
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setPadding(8,5,8,8);
        addTopButton(bottom, "MODIFICA", this::editSelected);
        addTopButton(bottom, "ELIMINA", this::deleteSelected);
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
    private void add(String type) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", UUID.randomUUID().toString()); o.put("type", type);
            o.put("x", 0.12); o.put("y", 0.12); o.put("w", type.equals("panel") ? 0.65 : 0.22);
            o.put("h", type.equals("panel") ? 0.28 : 0.14);
            o.put("text", type.equals("text") ? "TESTO" : type.equals("panel") ? "FINESTRA" : type.equals("button") ? "TASTO" : "KNOB");
            o.put("color", type.equals("panel") ? "#343A40" : "#E2B832");
            current().put(o); editor.selected = current().length()-1; editor.invalidate(); saveLocal();
        } catch(Exception ignored) {}
    }
    private void editSelected() {
        if (editor.selected < 0 || editor.selected >= current().length()) return;
        try {
            JSONObject o=current().getJSONObject(editor.selected);
            LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
            EditText label=new EditText(this); label.setSingleLine(); label.setHint("Testo / etichetta"); label.setText(o.optString("text"));
            EditText width=new EditText(this); width.setInputType(8194); width.setHint("Larghezza % (1-100)"); width.setText(String.valueOf((int)(o.optDouble("w")*100)));
            EditText height=new EditText(this); height.setInputType(8194); height.setHint("Altezza % (1-100)"); height.setText(String.valueOf((int)(o.optDouble("h")*100)));
            box.addView(label); box.addView(width); box.addView(height);
            new AlertDialog.Builder(this).setTitle("Proprietà elemento").setView(box)
                .setPositiveButton("APPLICA",(d,w)->{
                    o.put("text",label.getText().toString());
                    o.put("w",clamp(parse(width.getText().toString(),20)/100.0,0.01,1));
                    o.put("h",clamp(parse(height.getText().toString(),14)/100.0,0.01,1));
                    editor.invalidate(); saveLocal();
                }).setNegativeButton("ANNULLA",null).show();
        } catch(Exception ignored) {}
    }
    private int parse(String s,int fallback){try{return Integer.parseInt(s);}catch(Exception e){return fallback;}}
    private double clamp(double x,double a,double b){return Math.max(a,Math.min(b,x));}
    private void deleteSelected(){if(editor.selected>=0&&editor.selected<current().length()){JSONArray n=new JSONArray();for(int i=0;i<current().length();i++)if(i!=editor.selected)n.put(current().opt(i));layouts.put(mode,n);editor.selected=-1;editor.invalidate();saveLocal();}}
    private void updateStatus(){if(status!=null)status.setText(mode+"  ·  "+current().length()+" elementi  ·  Trascina per spostare, usa l'angolo per ridimensionare");}
    private void saveLocal(){getPreferences(0).edit().putString("project",toJson().toString()).apply(); if(status!=null)updateStatus();}
    private void loadLocal(){try{JSONObject p=new JSONObject(getPreferences(0).getString("project","{}"));for(String m:modes)if(p.has(m))layouts.put(m,p.getJSONArray(m));}catch(Exception ignored){}}
    private JSONObject toJson(){JSONObject p=new JSONObject();try{p.put("project","FREQUALIZER Layout Designer");p.put("version",1);p.put("devices",new JSONObject().put("tablet","Samsung Galaxy Tab A11+ SM-X230").put("smartphone","Samsung Galaxy S25 base"));for(String m:modes)p.put(m,layouts.get(m));}catch(Exception ignored){}return p;}
    private void export(){saveLocal();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"FREQUALIZER_layout_record.json");startActivityForResult(i,EXPORT);}
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(r==EXPORT&&c==RESULT_OK&&d!=null){try(OutputStream os=getContentResolver().openOutputStream(d.getData())){os.write(toJson().toString(2).getBytes(StandardCharsets.UTF_8));Toast.makeText(this,"Record esportato",Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,"Errore esportazione",Toast.LENGTH_LONG).show();}}}

    private class EditorView extends View {
        Paint p=new Paint(3); int selected=-1; float downX,downY,startX,startY; boolean resizing=false;
        EditorView(){super(MainActivity.this);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        protected void onDraw(Canvas c){super.onDraw(c);c.drawColor(Color.rgb(18,20,23));float pad=18, cw=getWidth()-pad*2,ch=getHeight()-pad*2;float ratio=mode.contains("ORIZZONTALE")?1.65f:0.62f;float w=cw,h=ch;if(w/h>ratio)w=h*ratio;else h=w/ratio;float left=(getWidth()-w)/2,top=(getHeight()-h)/2;
            p.setColor(Color.rgb(235,235,235));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);c.drawRoundRect(left,top,left+w,top+h,12,12,p);
            p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(35,38,43));c.drawRoundRect(left+2,top+2,left+w-2,top+h-2,10,10,p);
            JSONArray a=current();for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;float x=left+(float)o.optDouble("x")*w,y=top+(float)o.optDouble("y")*h,ww=(float)o.optDouble("w")*w,hh=(float)o.optDouble("h")*h;
                int color;try{color=Color.parseColor(o.optString("color","#DDB833"));}catch(Exception e){color=Color.YELLOW;}
                String type=o.optString("type");p.setColor(color);p.setStyle(type.equals("text")?Paint.Style.FILL:Paint.Style.FILL);if(type.equals("knob")){float rad=Math.min(ww,hh)*.38f;c.drawCircle(x+ww/2,y+hh/2,rad,p);p.setColor(Color.DKGRAY);c.drawCircle(x+ww/2,y+hh/2,rad*.72f,p);p.setColor(Color.WHITE);c.drawLine(x+ww/2,y+hh/2,x+ww/2,y+hh/2-rad*.58f,p);}else if(type.equals("text")){p.setColor(Color.WHITE);p.setTextSize(Math.max(12,hh*.55f));c.drawText(o.optString("text"),x,y+hh*.7f,p);}else{c.drawRoundRect(x,y,x+ww,y+hh,12,12,p);p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(Math.max(12,Math.min(ww,hh)*.22f));c.drawText(o.optString("text"),x+ww/2,y+hh/2+p.getTextSize()*.35f,p);p.setTextAlign(Paint.Align.LEFT);}
                if(i==selected){p.setColor(Color.CYAN);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);c.drawRect(x,y,x+ww,y+hh,p);p.setStyle(Paint.Style.FILL);c.drawCircle(x+ww,y+hh,12,p);}
            }
            p.setColor(Color.LTGRAY);p.setTextSize(22);p.setTextAlign(Paint.Align.CENTER);c.drawText(mode,left+w/2,Math.max(26,top-8),p);p.setTextAlign(Paint.Align.LEFT);
        }
        public boolean onTouchEvent(android.view.MotionEvent e){float x=e.getX(),y=e.getY();if(e.getAction()==MotionEvent.ACTION_DOWN){downX=x;downY=y;selected=hit(x,y);resizing=false;if(selected>=0){JSONObject o=current().optJSONObject(selected);float ww=(float)o.optDouble("w")*(getWidth()-36),hh=(float)o.optDouble("h")*(getHeight()-36);resizing=Math.abs(x-(downX+ww))<32&&Math.abs(y-(downY+hh))<32;startX=(float)o.optDouble("x");startY=(float)o.optDouble("y");}invalidate();return true;}if(e.getAction()==MotionEvent.ACTION_MOVE&&selected>=0){try{JSONObject o=current().getJSONObject(selected);float dx=(x-downX)/(getWidth()-36),dy=(y-downY)/(getHeight()-36);if(resizing){o.put("w",clamp(o.optDouble("w")+dx,0.03,1));o.put("h",clamp(o.optDouble("h")+dy,0.03,1));}else{o.put("x",clamp(startX+dx,0,0.97));o.put("y",clamp(startY+dy,0,0.97));}invalidate();}catch(Exception ignored){}return true;}if(e.getAction()==MotionEvent.ACTION_UP){saveLocal();return true;}return true;}
        int hit(float x,float y){float w=getWidth()-36,h=getHeight()-36;for(int i=current().length()-1;i>=0;i--){JSONObject o=current().optJSONObject(i);if(o==null)continue;float xx=18+(float)o.optDouble("x")*w,yy=18+(float)o.optDouble("y")*h,ww=(float)o.optDouble("w")*w,hh=(float)o.optDouble("h")*h;if(x>=xx&&x<=xx+ww&&y>=yy&&y<=yy+hh)return i;}return -1;}
    }
}