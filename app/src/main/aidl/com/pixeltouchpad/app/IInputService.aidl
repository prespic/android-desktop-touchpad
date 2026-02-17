package com.pixeltouchpad.app;

interface IInputService {
    oneway void moveCursor(int displayId, float x, float y);
    oneway void click(int displayId, float x, float y);
    oneway void scroll(int displayId, float x, float y, float vScroll);
    String diagnose(int externalDisplayId);
    oneway void destroy();
}
