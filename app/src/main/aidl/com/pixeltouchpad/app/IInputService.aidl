package com.pixeltouchpad.app;

interface IInputService {
    /** Move cursor to absolute position on given display */
    void moveCursor(int displayId, float x, float y);

    /** Click (mouse down + up) at position */
    void click(int displayId, float x, float y);

    /** Scroll at position; positive vScroll = scroll up */
    void scroll(int displayId, float x, float y, float vScroll);

    /** Clean up resources */
    void destroy();
}
