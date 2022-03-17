package com.dynamicfeature.aim;

import android.content.Context;

import com.google.android.play.core.splitcompat.SplitCompat;

public class Application extends android.app.Application {
    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        SplitCompat.install(this);
    }
}
