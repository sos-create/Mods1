package com.soscreate.starlitpaper;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StarCatalogTest {
    @Test void generatesTheRequestedNumberOfStars() {
        assertEquals(5000,StarCatalog.generate(5000,true,12345L).size());
    }
    @Test void catalogueIsDeterministic() {
        List<StarCatalog.Star> a=StarCatalog.generate(100,true,91519505L);
        List<StarCatalog.Star> b=StarCatalog.generate(100,true,91519505L);
        assertEquals(a,b);
    }
    @Test void starDirectionsAreUnitVectors() {
        for(StarCatalog.Star s:StarCatalog.generate(1000,true,77L)) {
            double length=Math.sqrt(s.x()*s.x()+s.y()*s.y()+s.z()*s.z());
            assertEquals(1.0,length,1.0e-6);
            assertTrue(s.size()>=0.21f);
            assertTrue(s.red()>=0 && s.red()<=1);
            assertTrue(s.green()>=0 && s.green()<=1);
            assertTrue(s.blue()>=0 && s.blue()<=1);
        }
    }
    @Test void shaderNoiseIsBoundedAndDeterministic() {
        double n=SkyNoise.fbm(1.2,3.4,5.6,4);
        assertEquals(n,SkyNoise.fbm(1.2,3.4,5.6,4));
        assertTrue(n>=0 && n<=1);
    }
}
