package com.powercms.cms.publish;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.publish.entity.DeployTargetEntity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class LocalNginxPublisherTest {
 @TempDir Path temp; LocalNginxPublisher p=new LocalNginxPublisher(new ObjectMapper());
 private DeployTargetEntity target(){var t=new DeployTargetEntity();t.setId(UUID.randomUUID());t.setTargetType(DeployTargetEntity.TYPE_LOCAL_NGINX);t.setConfig("{\"basePath\":\""+temp.toString().replace("\\","\\\\")+"\"}");return t;}
 @Test void switchesPointerAndRollsBack()throws Exception{var t=target();Path a=temp.resolve("a"),b=temp.resolve("b");Files.createDirectory(a);Files.writeString(a.resolve("index.html"),"one");Files.createDirectory(b);Files.writeString(b.resolve("index.html"),"two");UUID r1=UUID.randomUUID(),r2=UUID.randomUUID();p.publish(t,r1,a);p.publish(t,r2,b);p.rollback(t,r1);Path pointer=temp.resolve("current.pointer");assertEquals("releases/"+r1,Files.readString(pointer));}
 @Test void rejectsMissingArtifact(){assertThrows(RuntimeException.class,()->p.publish(target(),UUID.randomUUID(),temp.resolve("missing")));}
}
