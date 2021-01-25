package com.unifyid.example.gaitsample;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;

import java.util.HashMap;

public class HashAdapter extends BaseAdapter {
    private HashMap<String, String> data = new HashMap<String, String>();
    private String[] keys;

    public HashAdapter(HashMap<String, String> data) {
        this.data = data;

    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public Object getItem(int position) {
        return data.get(keys[position]);
    }

    @Override
    public long getItemId(int arg) {
        return arg;
    }

    @Override
    public View getView(int pos, View convertView, ViewGroup parent) {
        this.keys = data.keySet().toArray(new String[data.size()]);
        String key = keys[pos];
        String value = getItem(pos).toString();

        if (convertView == null) {
            convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.secret_listview_item, parent, false);
        }

        TextView txtSecretName = convertView.findViewById(R.id.txtSecretName);
        TextView txtSecretValue = convertView.findViewById(R.id.txtSecretValue);
        Button btnDelete = convertView.findViewById(R.id.btnDeleteSecret);
        btnDelete.setTag(key);

        txtSecretName.setText(key);
        ;
        txtSecretValue.setText(value);

        return convertView;
    }
}
