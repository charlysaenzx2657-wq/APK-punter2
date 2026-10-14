package com.example.puntero;

oneway interface ICallback {
    void onDown(float x, float y);
    void onDoubleTap(float x, float y);
    void onTouch(float x, float y, boolean down);
    void onLog(String text);
}
