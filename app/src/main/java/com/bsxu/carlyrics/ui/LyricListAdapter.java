package com.bsxu.carlyrics.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.bsxu.carlyrics.R;
import com.bsxu.carlyrics.model.LyricLine;

import java.util.ArrayList;
import java.util.List;

public final class LyricListAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<LyricLine> items;
    private int activeIndex = -1;

    public LyricListAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
        this.items = new ArrayList<LyricLine>();
    }

    public void setItems(List<LyricLine> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        activeIndex = -1;
        notifyDataSetChanged();
    }

    public void setActiveIndex(int index) {
        if (activeIndex == index) {
            return;
        }
        activeIndex = index;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView textView;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_lyric_line, parent, false);
        }
        textView = (TextView) convertView.findViewById(R.id.lyricText);

        LyricLine line = items.get(position);
        boolean active = position == activeIndex;
        textView.setText(line.getText());
        textView.setTextColor(active ? 0xFFFFFFFF : 0xA8FFFFFF);
        textView.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, active ? 30f : 21f);
        textView.setAlpha(active ? 1f : 0.72f);
        textView.setScaleX(active ? 1f : 0.98f);
        textView.setScaleY(active ? 1f : 0.98f);
        textView.setShadowLayer(active ? 18f : 0f, 0f, 0f, active ? 0x66FFFFFF : 0x00000000);
        return convertView;
    }
}
