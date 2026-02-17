package com.pixeltouchpad.app;

interface IInputService {
    void moveCursor(int displayId, float x, float y);
    void click(int displayId, float x, float y);
    void scroll(int displayId, float x, float y, float vScroll);
    String diagnose();
    void destroy();
}
