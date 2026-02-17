package com.pixeltouchpad.app;

interface IInputService {
    oneway void moveCursor(int displayId, float x, float y);
    oneway void click(int displayId, float x, float y);
    oneway void rightClick(int displayId, float x, float y);
    oneway void scroll(int displayId, float x, float y, float vScroll);
    oneway void startDrag(int displayId);
    oneway void endDrag(int displayId);
    oneway void sendKeyEvent(int displayId, int keyCode);
    oneway void sendShellCommand(int displayId, String command);
    String diagnose(int externalDisplayId);
    oneway void destroy();
}
