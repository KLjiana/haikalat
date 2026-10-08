package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import org.joml.Vector3f;

/** Camera paths shared with the existing frozen no-fog captures. */
final class SceneCaptureRoutes {
    private SceneCaptureRoutes() { }
    static void apply(Camera camera,String path,int frame) {
        switch(path) {
            case "static", "overview" -> { }
            case "deterministic_arc", "forest_arc" -> forest(camera,Math.min(1,frame/420f));
            case "street" -> {
                float loop=(frame%480)/480f,phase=loop<.5f?loop*2:(1-loop)*2,eased=phase*phase*(3-2*phase);
                camera.setPosition(new Vector3f(3.6f,4.2f,24-38*eased));
                camera.setYaw(-90+5*(float)Math.sin(frame*.01f));camera.setPitch(-3);
            }
            case "town_fog_street" -> {
                float loop=(frame%480)/480f,phase=loop<.5f?loop*2:(1-loop)*2,eased=phase*phase*(3-2*phase);
                camera.setPosition(new Vector3f(1f,1.7f,24-38*eased));
                camera.setYaw(-90+5*(float)Math.sin(frame*.01f));camera.setPitch(-3);
            }
            case "threshold" -> {
                camera.setPosition(new Vector3f(-4.8f-5.7f*Math.min(1,frame/300f),1.7f,12));
                camera.setYaw(180-5*Math.min(1,frame/300f));camera.setPitch(-3);
            }
            case "lab_rotate" -> { camera.setYaw(-90+12*(float)Math.sin(frame*.008f)); }
            default -> throw new IllegalArgumentException("unsupported capture cameraPath "+path);
        }
    }
    static void forest(Camera camera,float progress) {
        float angle=progress*(float)(Math.PI*.9)-(float)(Math.PI*.45);
        camera.setPosition(new Vector3f((float)Math.sin(angle)*4.8f,
                2.2f+(float)Math.sin(progress*Math.PI)*.6f,12.5f-progress*4));
        camera.setYaw(-90+(float)Math.sin(angle)*13);camera.setPitch(-4+(float)Math.cos(angle)*2);
    }
}
