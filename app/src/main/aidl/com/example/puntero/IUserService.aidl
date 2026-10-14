package com.example.puntero;

import com.example.puntero.ICallback;

interface IUserService {
    void destroy() = 16777114;
    void start(ICallback cb, int sw, int sh, int rotation, float linePortrait, float lineLand, boolean enabled) = 1;
    void update(int sw, int sh, int rotation, float linePortrait, float lineLand, boolean enabled) = 2;
    void stop() = 3;
}
