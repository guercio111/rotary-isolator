package com.example.rotaryisolator;

interface IShizukuReverbService {
    String startReverb(int mix, int decay, int size, int damping);
    void stopReverb();
    void destroy() = 16777114;
}