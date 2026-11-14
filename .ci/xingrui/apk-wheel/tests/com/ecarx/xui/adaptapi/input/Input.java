package com.ecarx.xui.adaptapi.input;
import android.content.Context;
public class Input {
    public static Input current = new Input();
    public IKeyCallback callback;
    public int[] codes;
    public int requests, abandons;
    public boolean failRequest;
    public static Input create(Context context) { return current; }
    public int[] requestKeysInterception(int[] codes, IKeyCallback callback) {
        this.codes = codes; this.callback = callback; requests++;
        if (failRequest) throw new IllegalStateException("fixture partial registration");
        return codes;
    }
    public boolean abandonKeysInterception(IKeyCallback callback) {
        if (callback != this.callback) throw new AssertionError("wrong callback abandoned");
        abandons++; return true;
    }
}
