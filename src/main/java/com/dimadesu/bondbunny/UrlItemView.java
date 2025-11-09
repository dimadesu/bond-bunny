package com.dimadesu.bondbunny;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

public class UrlItemView extends LinearLayout {
    private TextView labelView;
    private TextView urlTextView;
    private View urlLayout;

    public UrlItemView(Context context) {
        super(context);
        init(context);
    }

    public UrlItemView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public UrlItemView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.url_item, this, true);
        
        labelView = findViewById(R.id.label);
        urlTextView = findViewById(R.id.url_text);
        urlLayout = findViewById(R.id.url_layout);
        
        urlLayout.setOnClickListener(v -> copyUrlToClipboard());
    }

    public void setLabel(String label) {
        labelView.setText(label);
    }

    public void setUrl(String url) {
        urlTextView.setText(url);
    }

    public TextView getUrlTextView() {
        return urlTextView;
    }

    public void show() {
        labelView.setVisibility(VISIBLE);
        urlLayout.setVisibility(VISIBLE);
    }

    public void hide() {
        labelView.setVisibility(GONE);
        urlLayout.setVisibility(GONE);
    }

    private void copyUrlToClipboard() {
        String url = urlTextView.getText().toString();
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("SRT URL", url);
        clipboard.setPrimaryClip(clip);
        // On Android 12+ (API 31+), the system automatically shows a toast/notification
        // when content is copied to clipboard, so we don't need to show our own
    }
}
