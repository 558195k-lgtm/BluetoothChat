package com.btchat;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.util.SparseBooleanArray;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** בחירת איש קשר לשיחה פרטית, או בחירת חברים ליצירת קבוצה. */
public class ContactsActivity extends BaseActivity {
    private boolean groupMode;
    private ListView lv;
    private ArrayAdapter<String> ad;
    private final List<String[]> data = new ArrayList<String[]>();
    private final List<String> labels = new ArrayList<String>();
    private EditText nameEt;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        groupMode = "group_new".equals(getIntent().getStringExtra("mode"));
        setTitle(groupMode ? "קבוצה חדשה" : "שיחה חדשה");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        if (groupMode) {
            nameEt = new EditText(this);
            nameEt.setHint("שם הקבוצה");
            nameEt.setSingleLine(true);
            root.addView(nameEt, new LinearLayout.LayoutParams(-1, -2));
        }
        lv = new ListView(this);
        ad = new ArrayAdapter<String>(this, groupMode ? android.R.layout.simple_list_item_multiple_choice
                : android.R.layout.simple_list_item_1, labels);
        lv.setAdapter(ad);
        if (groupMode) lv.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        root.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1));

        if (!groupMode) {
            lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                    String uid = data.get(pos)[0];
                    Repo.ensurePrivate(ContactsActivity.this, uid);
                    startActivity(new Intent(ContactsActivity.this, ChatActivity.class).putExtra("conv", "p:" + uid));
                    finish();
                }
            });
            lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                    final String[] c = data.get(pos);
                    final boolean blocked = "1".equals(c[2]);
                    new AlertDialog.Builder(ContactsActivity.this).setTitle(c[1])
                            .setItems(new String[]{blocked ? "בטל חסימה" : "חסום"}, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) { Repo.setBlocked(ContactsActivity.this, c[0], !blocked); onUpdate(); }
                            }).show();
                    return true;
                }
            });
        } else {
            Button ok = new Button(this);
            ok.setText("צור קבוצה");
            ok.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { create(); } });
            root.addView(ok, new LinearLayout.LayoutParams(-1, -2));
        }
        setContentView(root);
    }

    private void create() {
        String name = nameEt.getText().toString().trim();
        SparseBooleanArray chk = lv.getCheckedItemPositions();
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < data.size(); i++) if (chk.get(i)) ids.add(data.get(i)[0]);
        if (name.length() == 0) { Toast.makeText(this, "נא להזין שם קבוצה", Toast.LENGTH_SHORT).show(); return; }
        if (ids.isEmpty()) { Toast.makeText(this, "נא לבחור לפחות חבר אחד", Toast.LENGTH_SHORT).show(); return; }
        String gid = Repo.newGroupId();
        Repo.updateGroup(this, gid, name, ids);
        startActivity(new Intent(this, ChatActivity.class).putExtra("conv", gid));
        finish();
    }

    @Override protected void onUpdate() {
        data.clear(); labels.clear();
        for (String[] c : Repo.contacts(this)) {
            if (groupMode && "1".equals(c[2])) continue;
            data.add(c);
            labels.add(c[1] + ("1".equals(c[2]) ? "  (חסום)" : "") + (BtService.connectedNames.containsKey(c[0]) ? "   ● מחובר" : ""));
        }
        ad.notifyDataSetChanged();
        if (data.isEmpty()) Toast.makeText(this, "אין אנשי קשר עדיין. התחבר למכשיר דרך 'מכשירים'.", Toast.LENGTH_LONG).show();
    }
}
