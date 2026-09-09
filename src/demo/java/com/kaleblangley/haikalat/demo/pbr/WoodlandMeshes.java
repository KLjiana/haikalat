package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;

/** Original, deterministic demo geometry; flat normals preserve the illustrated woodland style. */
final class WoodlandMeshes implements AutoCloseable {
    private final List<Mesh> owned = new ArrayList<>();
    final Mesh terrain, path, trunk, crown, rock, grass;

    WoodlandMeshes() {
        try {
            Builder land = new Builder();
            for (int z = -80; z < 24; z += 3) for (int x = -54; x < 54; x += 3) {
                Vector3f a = ground(x,z), b = ground(x,z+3), c = ground(x+3,z), d = ground(x+3,z+3);
                land.triangle(a,b,c); land.triangle(c,b,d);
            }
            terrain = own(land.data("woodland-ground"));
            Builder trail = new Builder();
            for (int z = -64; z < 20; z++) {
                float width = 1.25f + 0.18f * (float)Math.sin(z * 0.3);
                Vector3f a = ground(pathX(z)-width,z).add(0,0.12f,0);
                Vector3f b = ground(pathX(z+1)-width,z+1).add(0,0.12f,0);
                Vector3f c = ground(pathX(z)+width,z).add(0,0.12f,0);
                Vector3f d = ground(pathX(z+1)+width,z+1).add(0,0.12f,0);
                trail.triangle(a,b,c); trail.triangle(c,b,d);
            }
            path = own(trail.data("woodland-path"));
            Builder wood = new Builder();
            wood.branch(new Vector3f(), new Vector3f(0.15f,7,0), 0.34f,0.10f);
            for (int i=0;i<4;i++) {
                float angle = i * 2.4f;
                wood.branch(new Vector3f(0,3.7f+i*.5f,0),
                        new Vector3f((float)Math.cos(angle)*1.8f,5.5f+i*.45f,(float)Math.sin(angle)*1.8f), .13f,.035f);
            }
            trunk = own(wood.data("woodland-branches"));
            MeshData faceted = PbrSphereMesh.create(9,5);
            Builder leaves = new Builder();
            leaves.append(faceted,new Matrix4f().translation(-1.1f,6.3f,0).scale(2.2f,1.35f,2.0f));
            leaves.append(faceted,new Matrix4f().translation(1.25f,6.8f,.35f).scale(2.15f,1.55f,1.8f));
            leaves.append(faceted,new Matrix4f().translation(0,7.85f,-.35f).scale(2.0f,1.55f,2.0f));
            crown = own(leaves.data("woodland-crown"));
            Builder stone = new Builder();
            stone.append(PbrSphereMesh.create(7,4),new Matrix4f().scale(1,.75f,.85f));
            rock = own(stone.data("woodland-stone"));
            Builder blades = new Builder();
            for(int i=0;i<7;i++) {
                float angle=i*2.4f, x=(float)Math.cos(angle)*.3f,z=(float)Math.sin(angle)*.3f;
                Vector3f a=new Vector3f(x-.09f,0,z),b=new Vector3f(x+.09f,0,z);
                Vector3f tip=new Vector3f(x+.18f,.45f+(i%3)*.12f,z-.15f);
                blades.triangle(a,b,tip); blades.triangle(b,a,tip);
            }
            grass = own(blades.data("woodland-grass"));
        } catch (RuntimeException | Error failure) {
            try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static float pathX(float z) { return 1.7f * (float)Math.sin(z*.085f) + 1.0f; }
    static float height(float x,float z) {
        return -.7f + .5f*(float)Math.sin(z*.10f) + .28f*(float)Math.cos(x*.27f+z*.07f)
                + .008f*x*x + .004f*Math.max(0,-z-32)*Math.max(0,-z-32);
    }
    private static Vector3f ground(float x,float z) { return new Vector3f(x,height(x,z),z); }
    private Mesh own(MeshData data) { Mesh mesh=Mesh.from(data); owned.add(mesh); return mesh; }
    @Override public void close() {
        RuntimeException failure=null;
        for(int i=owned.size()-1;i>=0;i--) try { owned.get(i).close(); }
        catch(RuntimeException error) { if(failure==null) failure=error; else failure.addSuppressed(error); }
        owned.clear(); if(failure!=null) throw failure;
    }

    private static final class Builder {
        final List<Float> vertices = new ArrayList<>();
        void triangle(Vector3f a,Vector3f b,Vector3f c) {
            Vector3f n=new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
            if(n.lengthSquared()<1e-9f) return;
            n.normalize();
            Vector3f tangent=Math.abs(n.y)<.99f ? new Vector3f(0,1,0).cross(n).normalize() : new Vector3f(1,0,0);
            for(Vector3f p:List.of(a,b,c)) {
                float[] row={p.x,p.y,p.z,p.x*.2f,p.z*.2f,n.x,n.y,n.z,tangent.x,tangent.y,tangent.z,1};
                for(float value:row) vertices.add(value);
            }
        }
        void append(MeshData data,Matrix4f transform) {
            float[] source=data.vertices(); int[] indices=data.indices();
            for(int i=0;i<indices.length;i+=3) {
                Vector3f[] p=new Vector3f[3];
                for(int j=0;j<3;j++) { int v=indices[i+j]*12; p[j]=transform.transformPosition(new Vector3f(source[v],source[v+1],source[v+2])); }
                triangle(p[0],p[1],p[2]);
            }
        }
        void branch(Vector3f start,Vector3f end,float radius,float tipRadius) {
            Vector3f direction=new Vector3f(end).sub(start);
            Matrix4f transform=new Matrix4f().translation(start).rotate(new Quaternionf().rotationTo(new Vector3f(0,1,0),new Vector3f(direction).normalize()));
            for(int i=0;i<7;i++) {
                float a=(float)(i*Math.PI*2/7),b=(float)((i+1)*Math.PI*2/7);
                Vector3f p=transform.transformPosition(new Vector3f((float)Math.cos(a)*radius,0,(float)Math.sin(a)*radius));
                Vector3f q=transform.transformPosition(new Vector3f((float)Math.cos(b)*radius,0,(float)Math.sin(b)*radius));
                Vector3f r=transform.transformPosition(new Vector3f((float)Math.cos(a)*tipRadius,direction.length(),(float)Math.sin(a)*tipRadius));
                Vector3f s=transform.transformPosition(new Vector3f((float)Math.cos(b)*tipRadius,direction.length(),(float)Math.sin(b)*tipRadius));
                triangle(p,r,q); triangle(q,r,s); triangle(r,end,s);
            }
        }
        MeshData data(String name) {
            float[] data=new float[vertices.size()]; for(int i=0;i<data.length;i++) data[i]=vertices.get(i);
            return MeshData.of(name,data,PbrSphereMesh.create(3,2).layout());
        }
    }
}
