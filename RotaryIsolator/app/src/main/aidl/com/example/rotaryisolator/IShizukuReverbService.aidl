package com.example.rotaryisolator;

interface IShizukuReverbService {
    String startReverb(int mix, int decay, int size, int damping) = 1;
    void stopReverb() = 2;
    void destroy() = 16777114;
}