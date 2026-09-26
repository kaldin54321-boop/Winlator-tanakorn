package com.winlator.renderer;

import android.opengl.GLES20;

import com.winlator.renderer.effects.Effect;
import com.winlator.renderer.effects.FrameGenerationEffect;
import com.winlator.renderer.material.ScreenMaterial;

import java.util.ArrayList;

public class EffectComposer {
    private final GLRenderer renderer;
    private RenderTarget readBuffer = null;
    private RenderTarget writeBuffer = null;
    private final ArrayList<Effect> effects = new ArrayList<>();
    private FrameGenerationEffect frameGenerationEffect;

    public EffectComposer(GLRenderer renderer) {
        this.renderer = renderer;
    }

    public synchronized void addEffect(Effect effect) {
        if (!effects.contains(effect)) {
            effects.add(effect);
            if (effect instanceof FrameGenerationEffect) frameGenerationEffect = (FrameGenerationEffect) effect;
        }
        renderer.xServerView.requestRender();
    }

    public synchronized void removeEffect(Effect effect) {
        if (effects.remove(effect) && effect == frameGenerationEffect) frameGenerationEffect = null;
        renderer.xServerView.requestRender();
    }

    public synchronized  <T extends Effect> T getEffect(Class<T> effectClass) {
        for (Effect effect : effects) {
            if (effect.getClass() == effectClass) return (T)effect;
        }
        return null;
    }

    public synchronized boolean hasEffects() {
        return !effects.isEmpty();
    }

    private void swapBuffers() {
        RenderTarget tmp = writeBuffer;
        writeBuffer = readBuffer;
        readBuffer = tmp;
    }

    private void renderEffect(Effect effect) {
        ScreenMaterial material = effect.getMaterial();
        material.use();
        if (effect instanceof FrameGenerationEffect) ((FrameGenerationEffect) effect).setupShaderUniforms();
        renderer.quadVertices.bind(material.programId);

        material.setUniformVec2(material.uniforms.resolution, renderer.surfaceWidth, renderer.surfaceHeight);


        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, readBuffer.getTextureId());
        material.setUniformInt(material.uniforms.screenTexture, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void initBuffers() {
        if (readBuffer == null) {
            readBuffer = new RenderTarget();
            readBuffer.allocateFramebuffer(renderer.surfaceWidth, renderer.surfaceHeight);
        }

        if (writeBuffer == null) {
            writeBuffer = new RenderTarget();
            writeBuffer.allocateFramebuffer(renderer.surfaceWidth, renderer.surfaceHeight);
        }
    }

    public synchronized void render() {
        initBuffers();

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, hasEffects() ? readBuffer.getFramebuffer() : 0);
        renderer.drawFrame();

        for (Effect effect : effects) {
            boolean renderToScreen = effect == effects.get(effects.size()-1);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, !renderToScreen ? writeBuffer.getFramebuffer() : 0);
            GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
            renderer.viewportNeedsUpdate = true;
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            renderEffect(effect);
            swapBuffers();
        }

        // Keep pumping extra frames while generation is on so the multiplied
        // pacing actually materializes (same continuous-render behavior as
        // the alexvorxx reference composer).
        if (frameGenerationEffect != null && frameGenerationEffect.isEnabled()) {
            renderer.xServerView.requestRender();
        }
    }

    public synchronized void configureFrameGeneration(int initialFPS, int mode) {
        if (frameGenerationEffect != null) {
            frameGenerationEffect.setInitialFPS(initialFPS);
            frameGenerationEffect.setGenerationMode(mode);
        }
        renderer.xServerView.requestRender();
    }

    public void setDisplayRefreshRate(int refreshRate) {
        if (frameGenerationEffect != null) frameGenerationEffect.setDisplayRefreshRate(refreshRate);
    }

    public void setFrameGenerationVariables(int generationMode, int fpsMultiplier, int apiMode,
                                            boolean usePostProcessing, boolean blendModeAuto, float motionScale) {
        if (frameGenerationEffect != null && frameGenerationEffect.isEnabled()) {
            int initialFPS = frameGenerationEffect.isAutoDetectFPS() ? FrameGenerationEffect.FPS_AUTO
                : frameGenerationEffect.getInitialFPS();
            frameGenerationEffect.toggleGeneration();
            removeEffect(frameGenerationEffect);
            FrameGenerationEffect recreated = new FrameGenerationEffect(renderer, generationMode, fpsMultiplier,
                apiMode, usePostProcessing, blendModeAuto, motionScale);
            addEffect(recreated);
            recreated.toggleGeneration();
            recreated.setInitialFPS(initialFPS);
        }
    }

    public synchronized FrameGenerationSettings getFrameGenerationSettings() {
        if (frameGenerationEffect != null) {
            return new FrameGenerationSettings(
                frameGenerationEffect.getInitialFPS(),
                frameGenerationEffect.isAutoDetectFPS(),
                frameGenerationEffect.getCurrentRealFrameInterval(),
                frameGenerationEffect.getCurrentTargetFrameInterval(),
                frameGenerationEffect.getFpsMultiplier());
        }
        return null;
    }

    public static class FrameGenerationSettings {
        public final int initialFPS;
        public final boolean autoDetect;
        public final long realInterval;
        public final long targetInterval;
        public final int fpsMultiplier;

        public FrameGenerationSettings(int initialFPS, boolean autoDetect, long realInterval,
                                       long targetInterval, int fpsMultiplier) {
            this.initialFPS = initialFPS;
            this.autoDetect = autoDetect;
            this.realInterval = realInterval;
            this.targetInterval = targetInterval;
            this.fpsMultiplier = fpsMultiplier;
        }
    }
}