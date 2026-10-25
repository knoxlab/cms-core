package com.powercms.cms.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.publish.entity.DeployTargetEntity;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class LocalNginxPublisher {
 private final ObjectMapper mapper; private final ConcurrentHashMap<UUID,ReentrantLock> locks=new ConcurrentHashMap<>();
 LocalNginxPublisher(ObjectMapper mapper){this.mapper=mapper;}
 public Path publish(DeployTargetEntity target, UUID releaseId, Path artifact){
  if(!DeployTargetEntity.TYPE_LOCAL_NGINX.equals(target.getTargetType())) throw new BusinessException(ErrorCodes.CONFLICT,"GIT target is unsupported");
  JsonNode c; try{c=mapper.readTree(target.getConfig());}catch(IOException e){throw new BusinessException(ErrorCodes.CONFLICT,"invalid target config");}
  String configured=c.path("basePath").asText(""); if(configured.isBlank())throw new BusinessException(ErrorCodes.CONFLICT,"basePath is required");
  Path base=Paths.get(configured).toAbsolutePath().normalize(), src=artifact.toAbsolutePath().normalize();
  if(!Files.isDirectory(src)||Files.isSymbolicLink(src))throw new BusinessException(ErrorCodes.CONFLICT,"invalid artifact");
  Path releases=base.resolve("releases").normalize(); if(!releases.startsWith(base))throw new BusinessException(ErrorCodes.CONFLICT,"unsafe basePath");
  ReentrantLock lock=locks.computeIfAbsent(target.getId(),k->new ReentrantLock()); lock.lock();
  try{Files.createDirectories(releases);Path dest=releases.resolve(releaseId.toString());if(Files.exists(dest,LinkOption.NOFOLLOW_LINKS)){switchPointer(base,dest);return dest;}Path tmp=releases.resolve("."+releaseId+".tmp-"+UUID.randomUUID());copy(src,tmp);validate(tmp);move(tmp,dest);switchPointer(base,dest);return dest;}catch(IOException e){throw new BusinessException(ErrorCodes.INTERNAL_ERROR,"publish failed: "+e.getMessage());}finally{lock.unlock();}
 }
 public Path rollback(DeployTargetEntity target, UUID releaseId){JsonNode c;try{c=mapper.readTree(target.getConfig());Path base=Paths.get(c.path("basePath").asText()).toAbsolutePath().normalize();Path dest=base.resolve("releases").resolve(releaseId.toString()).normalize();if(!dest.startsWith(base)||!Files.isDirectory(dest))throw new IOException("release missing");switchPointer(base,dest);return dest;}catch(IOException e){throw new BusinessException(ErrorCodes.CONFLICT,"rollback failed: "+e.getMessage());}}
 private void switchPointer(Path base,Path dest)throws IOException{Path ptr=base.resolve("current.pointer");Path ptmp=base.resolve(".current.pointer-"+UUID.randomUUID());Files.writeString(ptmp,"releases/"+dest.getFileName().toString(),StandardOpenOption.CREATE_NEW);move(ptmp,ptr);}
 private void move(Path a,Path b)throws IOException{try{Files.move(a,b,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(a,b,StandardCopyOption.REPLACE_EXISTING);}}
 private void copy(Path a,Path b)throws IOException{try(var s=Files.walk(a)){s.forEach(p->{if(Files.isSymbolicLink(p))throw new UncheckedIOException(new IOException("symlink denied"));Path q=b.resolve(a.relativize(p));try{if(Files.isDirectory(p))Files.createDirectories(q);else Files.copy(p,q);}catch(IOException e){throw new UncheckedIOException(e);}});}}
 private void validate(Path d)throws IOException{try(var s=Files.walk(d)){if(s.anyMatch(Files::isSymbolicLink))throw new IOException("symlink denied");}}
}
