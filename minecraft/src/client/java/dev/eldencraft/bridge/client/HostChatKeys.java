package dev.eldencraft.bridge.client;

import static org.lwjgl.sdl.SDLKeycode.*;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;

/** Explicit ECCH editing-key identifiers to 26.3 SDL scancode/keycode/modifier API. */
public final class HostChatKeys {
  public static KeyEvent event(int wire, int mods) {
    int scan =
        switch (wire) {
          case 256 -> InputConstants.KEY_ESCAPE;
          case 257 -> InputConstants.KEY_RETURN;
          case 258 -> InputConstants.KEY_TAB;
          case 259 -> InputConstants.KEY_BACKSPACE;
          case 260 -> InputConstants.KEY_INSERT;
          case 261 -> InputConstants.KEY_DELETE;
          case 262 -> InputConstants.KEY_RIGHT;
          case 263 -> InputConstants.KEY_LEFT;
          case 264 -> InputConstants.KEY_DOWN;
          case 265 -> InputConstants.KEY_UP;
          case 266 -> InputConstants.KEY_PAGEUP;
          case 267 -> InputConstants.KEY_PAGEDOWN;
          case 268 -> InputConstants.KEY_HOME;
          case 269 -> InputConstants.KEY_END;
          case 65 -> InputConstants.KEY_A;
          case 67 -> InputConstants.KEY_C;
          case 86 -> InputConstants.KEY_V;
          case 88 -> InputConstants.KEY_X;
          default -> throw new IllegalArgumentException("unsupported chat key");
        };
    int key =
        switch (wire) {
          case 256 -> SDLK_ESCAPE;
          case 257 -> SDLK_RETURN;
          case 258 -> SDLK_TAB;
          case 259 -> SDLK_BACKSPACE;
          case 260 -> SDLK_INSERT;
          case 261 -> SDLK_DELETE;
          case 262 -> SDLK_RIGHT;
          case 263 -> SDLK_LEFT;
          case 264 -> SDLK_DOWN;
          case 265 -> SDLK_UP;
          case 266 -> SDLK_PAGEUP;
          case 267 -> SDLK_PAGEDOWN;
          case 268 -> SDLK_HOME;
          case 269 -> SDLK_END;
          case 65 -> SDLK_A;
          case 67 -> SDLK_C;
          case 86 -> SDLK_V;
          case 88 -> SDLK_X;
          default -> throw new IllegalArgumentException("unsupported chat key");
        };
    if ((mods & ~15) != 0) throw new IllegalArgumentException("unsupported chat modifiers");
    int modifiers =
        ((mods & 1) != 0 ? InputConstants.MOD_SHIFT : 0)
            | ((mods & 2) != 0 ? InputConstants.MOD_CONTROL : 0)
            | ((mods & 4) != 0 ? InputConstants.MOD_ALT : 0)
            | ((mods & 8) != 0 ? InputConstants.MOD_SUPER : 0);
    return new KeyEvent(scan, key, modifiers);
  }

  private HostChatKeys() {}
}
