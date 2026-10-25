package com.powercms.cms.publish;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.publish.entity.BuildArtifactEntity;
import com.powercms.cms.publish.entity.BuildJobEntity;
import com.powercms.cms.publish.entity.ReleaseEntity;
import com.powercms.cms.publish.entity.ReleasePageEntity;
import com.powercms.cms.publish.repo.BuildArtifactRepository;
import com.powercms.cms.publish.repo.BuildJobRepository;
import com.powercms.cms.publish.repo.BuildLogRepository;
import com.powercms.cms.publish.repo.ReleasePageRepository;
import com.powercms.cms.publish.repo.ReleaseRepository;
import com.powercms.cms.sitepackage.entity.TemplateRevisionEntity;
import com.powercms.cms.sitepackage.repo.TemplateRevisionRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class BuildWorker {
    private final BuildJobRepository jobs;
    private final BuildArtifactRepository artifacts;
    private final BuildLogRepository logs;
    private final TemplateRevisionRepository revisions;
    private final ReleasePageRepository releasePages;
    private final ReleaseRepository releases;
    private final ReleaseStaticAssetExporter assetExporter;
    private final com.powercms.cms.identity.permission.ObjectPermissionService objectPermissions;
    private final ObjectMapper mapper;
    private final Path root;
    private final long timeoutMs;
    private final long maxBytes;
    private final long maxFiles;
    private final String mode;
    private final Map<UUID, Process> processes = new ConcurrentHashMap<>();

    BuildWorker(BuildJobRepository jobs, BuildArtifactRepository artifacts, BuildLogRepository logs,
                TemplateRevisionRepository revisions, ReleasePageRepository releasePages, ReleaseRepository releases,
                ReleaseStaticAssetExporter assetExporter,
                com.powercms.cms.identity.permission.ObjectPermissionService objectPermissions, ObjectMapper mapper,
                @Value("${powercms.build.workspace-root:${java.io.tmpdir}/powercms-builds}") String root,
                @Value("${powercms.build.timeout-ms:900000}") long timeoutMs,
                @Value("${powercms.build.max-output-bytes:1073741824}") long maxBytes,
                @Value("${powercms.build.max-files:100000}") long maxFiles,
                @Value("${powercms.build.mode:process}") String mode) {
        this.jobs = jobs; this.artifacts = artifacts; this.logs = logs; this.revisions = revisions;
        this.releasePages = releasePages; this.releases = releases; this.assetExporter = assetExporter;
        this.objectPermissions = objectPermissions; this.mapper = mapper;
        this.root = Paths.get(root).toAbsolutePath().normalize();
        this.timeoutMs = timeoutMs; this.maxBytes = maxBytes; this.maxFiles = maxFiles; this.mode = mode;
    }

    public void execute(UUID jobId) {
        BuildJobEntity job = jobs.findById(jobId).orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND,"\u6784\u5efa\u4efb\u52a1\u4e0d\u5b58\u5728"));
        Path workspace = null;
        try {
            boolean preview = BuildJobEntity.JOB_PREVIEW.equals(job.getJobType());
            boolean release = BuildJobEntity.JOB_RELEASE.equals(job.getJobType())
                || BuildJobEntity.JOB_RELEASE_INCREMENTAL.equals(job.getJobType());
            if (!preview && !release) throw new IllegalStateException("unsupported build type: " + job.getJobType());
            if (release && job.getReleaseId() == null) throw new IllegalStateException("releaseId is required for RELEASE builds");
            if (release) {
                buildFrozenRelease(job, workspace = Files.createDirectories(root.resolve(jobId.toString())));
                return;
            }
            TemplateRevisionEntity rev = revisions.findByTemplateProjectIdAndVersion(job.getTemplateProjectId(),job.getRevision())
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND,"\u7248\u672c\u4e0d\u5b58\u5728"));
            workspace = Files.createDirectories(root.resolve(jobId.toString()));
            writeSnapshot(rev.getFileSnapshot(), workspace);
            Path packageJson=workspace.resolve("package.json"), lock=workspace.resolve("package-lock.json");
            if (!Files.isRegularFile(packageJson) || !Files.isRegularFile(lock)) throw new IllegalStateException("Build requires package.json and package-lock.json");
            run(jobId, workspace, List.of("npm","ci"));
            run(jobId, workspace, List.of("npm","run","build"));
            if (isCancelled(jobId)) return;
            Path output=Files.isDirectory(workspace.resolve("dist"))?workspace.resolve("dist"):workspace.resolve("build");
            if (!Files.isDirectory(output)) throw new IllegalStateException("Build produced no dist or build directory");
            validateTree(output);
            Path artifact = outputPath(root, job);
            Files.createDirectories(artifact.getParent()); copyTree(output,artifact); validateTree(artifact);
            String digest=digestTree(artifact); String manifest=manifest(artifact);
            if (isCancelled(jobId)) { deleteTree(artifact); return; }
            if (release) {
                var record = new BuildArtifactEntity();
                record.setBuildJobId(jobId); record.setReleaseId(job.getReleaseId()); record.setArtifactPath(artifact.toString());
                record.setFileCount(manifest.isBlank() ? 0 : manifest.split("\n").length); record.setSizeBytes(treeSize(artifact));
                record.setFileManifest(manifest); record.setInputDigest(digest); record.setBuildType(BuildArtifactEntity.BUILD_FULL);
                artifacts.save(record);
                if (isCancelled(jobId)) { artifacts.delete(record); deleteTree(artifact); return; }
            }
            if (isCancelled(jobId)) { deleteTree(artifact); return; }
            jobs.findById(jobId).ifPresent(j->{ if(!isCancelled(jobId)){ j.setArtifactUrl(artifact.toString()); j.setStatus(BuildJobEntity.STATUS_SUCCESS); j.setFinishedAt(java.time.Instant.now()); jobs.save(j); logs.save(com.powercms.cms.publish.entity.BuildLogEntity.write(jobId, nextSeq(jobId),"INFO","DONE digest="+digest)); }});
        } catch (Exception e) {
            jobs.findById(jobId).ifPresent(j->{ if(!BuildJobEntity.STATUS_CANCELLED.equals(j.getStatus())){j.setStatus(BuildJobEntity.STATUS_FAILED);j.setFinishedAt(java.time.Instant.now());jobs.save(j);logs.save(com.powercms.cms.publish.entity.BuildLogEntity.write(jobId,nextSeq(jobId),"ERROR",e.getMessage()));}});
        } finally { processes.remove(jobId); if(workspace!=null) deleteTree(workspace); }
    }

    private void buildFrozenRelease(BuildJobEntity job, Path workspace) throws Exception {
        var release = releases.findById(job.getReleaseId()).orElseThrow(() -> new IllegalStateException("release not found"));
        if (!ReleaseEntity.STATUS_FROZEN.equals(release.getStatus())) throw new IllegalStateException("release is not frozen");
        boolean wantIncremental = BuildJobEntity.JOB_RELEASE_INCREMENTAL.equals(job.getJobType());
        Path artifact = outputPath(root, job);
        Files.createDirectories(artifact.getParent());
        String buildType = BuildArtifactEntity.BUILD_FULL;
        UUID baseArtifactId = null;
        String changedManifest = null;
        String deletedManifest = null;
        String fallbackReason = null;

        if (wantIncremental) {
            var baseline = findBaseline(release);
            if (baseline.isEmpty()) {
                fallbackReason = "no baseline artifact";
            } else {
                var base = baseline.get();
                var baseRelease = releases.findById(base.getReleaseId()).orElse(null);
                Path basePath = Path.of(base.getArtifactPath());
                if (baseRelease == null || baseRelease.getGlobalInputDigest() == null || release.getGlobalInputDigest() == null) {
                    fallbackReason = "global_input_digest missing";
                } else if (!Objects.equals(baseRelease.getGlobalInputDigest(), release.getGlobalInputDigest())) {
                    fallbackReason = "non-page dependencies changed";
                } else if (!Files.isDirectory(basePath)) {
                    fallbackReason = "baseline artifact missing on disk";
                } else {
                    buildType = BuildArtifactEntity.BUILD_INCREMENTAL;
                    baseArtifactId = base.getId();
                    copyTree(basePath, artifact);
                    var diff = IncrementalReleaseDiff.compare(
                        releasePages.findByReleaseIdOrderByRoutePathAsc(base.getReleaseId()),
                        releasePages.findByReleaseIdOrderByRoutePathAsc(release.getId()));
                    StringBuilder changed = new StringBuilder();
                    StringBuilder deleted = new StringBuilder();
                    var visible = visibleRouteSet(release.getId());
                    for (var removed : diff.removed()) {
                        dropPage(artifact, removed.routePath());
                        deleted.append(IncrementalReleaseDiff.relativeHtmlPath(removed.routePath())).append('\n');
                    }
                    for (ReleasePageEntity page : releasePages.findByReleaseIdOrderByRoutePathAsc(release.getId())) {
                        if (page.getRoutePath() == null || visible.contains(page.getRoutePath())) continue;
                        dropPage(artifact, page.getRoutePath());
                        deleted.append(IncrementalReleaseDiff.relativeHtmlPath(page.getRoutePath())).append('\n');
                    }
                    for (var page : diff.added()) {
                        if (!visible.contains(page.routePath())) continue;
                        writePage(artifact, page.routePath(), page.htmlContent(), changed);
                    }
                    for (var page : diff.changed()) {
                        if (!visible.contains(page.routePath())) continue;
                        writePage(artifact, page.routePath(), page.htmlContent(), changed);
                    }
                    Files.writeString(artifact.resolve("routes.txt"), String.join("\n", visible) + (visible.isEmpty() ? "" : "\n"), StandardCharsets.UTF_8);
                    var export = assetExporter.export(release.getId(), artifact, release.getSiteId());
                    append(job.getId(), "INFO", "INCREMENTAL added=" + diff.addedCount()
                        + " changed=" + diff.changedCount()
                        + " removed=" + diff.removedCount()
                        + " unchanged=" + diff.unchangedCount()
                        + " mediaCopied=" + export.mediaCopied()
                        + " redirects=" + export.redirectsExported());
                    changedManifest = changed.toString();
                    deletedManifest = deleted.toString();
                }
            }
            if (fallbackReason != null) {
                append(job.getId(), "WARN", "INCREMENTAL fallback to FULL: " + fallbackReason);
            }
        }

        if (BuildArtifactEntity.BUILD_FULL.equals(buildType)) {
            if (Files.exists(artifact)) deleteTree(artifact);
            Files.createDirectories(artifact);
            StringBuilder routes = new StringBuilder();
            for (ReleasePageEntity page : releasePages.findByReleaseIdOrderByRoutePathAsc(job.getReleaseId())) {
                if (!anonymouslyVisible(page)) continue;
                writePage(artifact, page.getRoutePath(), page.getHtmlContent(), null);
                if (page.getRoutePath() != null && !page.getRoutePath().isBlank()) {
                    routes.append(page.getRoutePath()).append('\n');
                }
            }
            Files.writeString(artifact.resolve("routes.txt"), routes.toString(), StandardCharsets.UTF_8);
            var export = assetExporter.export(release.getId(), artifact, release.getSiteId());
            append(job.getId(), "INFO", "FULL mediaCopied=" + export.mediaCopied()
                + " mediaMissing=" + export.mediaMissing()
                + " redirects=" + export.redirectsExported()
                + " playbackEntries=" + export.playbackEntries());
        }

        validateTree(artifact);
        String digest = digestTree(artifact), fileManifest = manifest(artifact);
        var record = new BuildArtifactEntity();
        record.setBuildJobId(job.getId());
        record.setReleaseId(job.getReleaseId());
        record.setArtifactPath(artifact.toString());
        record.setFileCount(fileManifest.isBlank() ? 0 : fileManifest.split("\n").length);
        record.setSizeBytes(treeSize(artifact));
        record.setRouteManifest(release.getRouteManifest());
        record.setFileManifest(fileManifest);
        record.setInputDigest(release.getInputDigest());
        record.setArtifactDigest(digest);
        record.setBuildType(buildType);
        record.setBaseArtifactId(baseArtifactId);
        record.setChangedFileManifest(changedManifest);
        record.setDeletedFileManifest(deletedManifest);
        artifacts.save(record);
        final String finalBuildType = buildType;
        final String finalDigest = digest;
        jobs.findById(job.getId()).ifPresent(j -> {
            j.setArtifactUrl(artifact.toString());
            j.setStatus(BuildJobEntity.STATUS_SUCCESS);
            j.setFinishedAt(java.time.Instant.now());
            jobs.save(j);
            logs.save(com.powercms.cms.publish.entity.BuildLogEntity.write(j.getId(), nextSeq(j.getId()), "INFO",
                "DONE frozen-release type=" + finalBuildType + " digest=" + finalDigest));
        });
    }

    private Optional<BuildArtifactEntity> findBaseline(ReleaseEntity target) {
        List<UUID> priorReleaseIds = releases.findBySiteIdOrderByCreatedAtDesc(target.getSiteId()).stream()
            .map(ReleaseEntity::getId)
            .filter(id -> !id.equals(target.getId()))
            .toList();
        if (priorReleaseIds.isEmpty()) return Optional.empty();
        return artifacts.findByReleaseIdInOrderByCreatedAtDesc(priorReleaseIds).stream().findFirst();
    }

    private java.util.Set<String> visibleRouteSet(UUID releaseId) {
        java.util.Set<String> routes = new java.util.LinkedHashSet<>();
        for (ReleasePageEntity page : releasePages.findByReleaseIdOrderByRoutePathAsc(releaseId)) {
            if (!anonymouslyVisible(page) || page.getRoutePath() == null || page.getRoutePath().isBlank()) continue;
            routes.add(page.getRoutePath());
        }
        return routes;
    }

    private com.powercms.cms.identity.permission.ProgramAccessService programAccess;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProgramAccess(com.powercms.cms.identity.permission.ProgramAccessService programAccess) {
        this.programAccess = programAccess;
    }

    private boolean anonymouslyVisible(ReleasePageEntity page) {
        if (page.getPageId() == null) return false;
        if (programAccess != null && programAccess.variesByRequest(
            com.powercms.cms.identity.permission.ObjectType.CONTENT, page.getPageId())) {
            return false;
        }
        return objectPermissions.hasAnonymousRight(
            com.powercms.cms.identity.permission.ObjectType.CONTENT,
            page.getPageId(),
            com.powercms.cms.identity.permission.ObjectType.VIEW);
    }

    private static void dropPage(Path artifact, String route) throws IOException {
        Path file = safe(artifact, IncrementalReleaseDiff.relativeHtmlPath(route));
        Files.deleteIfExists(file);
        deleteEmptyParents(file.getParent(), artifact);
    }

    private static void writePage(Path artifact, String route, String html, StringBuilder changed) throws IOException {
        String relative = IncrementalReleaseDiff.relativeHtmlPath(route);
        Path file = safe(artifact, relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, html == null ? "" : html, StandardCharsets.UTF_8);
        if (changed != null) changed.append(relative).append('\n');
    }

    private static void deleteEmptyParents(Path dir, Path root) throws IOException {
        Path current = dir;
        while (current != null && current.startsWith(root) && !current.equals(root)) {
            try (var stream = Files.list(current)) {
                if (stream.findAny().isPresent()) break;
            }
            Files.deleteIfExists(current);
            current = current.getParent();
        }
    }

    private void writeSnapshot(String snapshot,Path ws) throws IOException { List<Map<String,Object>> fs=mapper.readValue(snapshot,new TypeReference<>(){}); for(var f:fs){String n=(String)f.get("path"); Path p=safe(ws,n); if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)) throw new SecurityException("symlink/path collision"); Files.createDirectories(p.getParent()); Files.writeString(p,(String)f.getOrDefault("content",""),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}}
    static Path safe(Path root,String name){ if(name==null||name.isBlank()||name.startsWith("/")||name.startsWith("\\")||name.matches("^[A-Za-z]:.*")||name.contains(":"))throw new SecurityException("absolute/ADS path"); for(String part:name.replace('\\','/').split("/")){String stem=part.contains(".")?part.substring(0,part.indexOf('.')):part;if(part.equals("..")||stem.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))throw new SecurityException("unsafe path");} Path p=root.resolve(name).normalize(); if(!p.startsWith(root))throw new SecurityException("path traversal"); return p; }
    private void run(UUID id,Path dir,List<String> cmd) throws Exception {
        List<String> actual=new ArrayList<>(cmd);
        if("docker".equalsIgnoreCase(mode)) { var docker=new ArrayList<String>(List.of("docker","run","--rm","--network=none","-v",dir+":/workspace","-w","/workspace","node:22-alpine")); docker.addAll(cmd); actual=docker; }
        Process p=new ProcessBuilder(actual).directory(dir.toFile()).redirectErrorStream(true).start();
        processes.put(id,p);
        AtomicReference<Throwable> streamFailure = new AtomicReference<>();
        Thread outputReader = Thread.ofVirtual().start(() -> {
            try(var r=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))){
                String line; while((line=r.readLine())!=null) append(id,"INFO",line);
            } catch (Throwable e) { streamFailure.set(e); }
        });
        boolean finished = p.waitFor(timeoutMs,TimeUnit.MILLISECONDS);
        if(!finished){
            p.descendants().forEach(h->h.destroyForcibly()); p.destroyForcibly();
            p.waitFor(5,TimeUnit.SECONDS); outputReader.join(5000);
            throw new TimeoutException("build timeout after " + timeoutMs + "ms");
        }
        outputReader.join(5000);
        if(outputReader.isAlive()) throw new IllegalStateException("build output stream did not close");
        if(streamFailure.get()!=null && !isCancelled(id)) throw new IOException("failed to stream build output",streamFailure.get());
        if(isCancelled(id)) throw new CancellationException();
        if(p.exitValue()!=0)throw new IllegalStateException("command failed: "+String.join(" ",cmd));
    }
    static Path outputPath(Path root, BuildJobEntity job) {
        if (BuildJobEntity.JOB_PREVIEW.equals(job.getJobType()))
            return root.resolve("previews").resolve(job.getTemplateProjectId().toString()).resolve(Integer.toString(job.getRevision())).resolve(job.getId().toString());
        return root.resolve("artifacts").resolve(job.getId().toString());
    }
    private boolean isCancelled(UUID id){return jobs.findById(id).map(j->BuildJobEntity.STATUS_CANCELLED.equals(j.getStatus())).orElse(true);}
    public void cancel(UUID id){Process p=processes.get(id);if(p!=null){p.descendants().forEach(h->h.destroyForcibly());p.destroyForcibly();}}
    private int nextSeq(UUID id){var a=logs.findByBuildJobIdOrderBySeq(id);return a.isEmpty()?0:a.get(a.size()-1).getSeq()+1;}
    private void append(UUID id,String level,String line){logs.save(com.powercms.cms.publish.entity.BuildLogEntity.write(id,nextSeq(id),level,line));}
    private void validateTree(Path d)throws IOException{try(var s=Files.walk(d)){long[] x={0,0};s.forEach(p->{try{if(Files.isSymbolicLink(p))throw new SecurityException("symlink denied");if(Files.isRegularFile(p)){x[0]++;x[1]+=Files.size(p);}}catch(IOException e){throw new UncheckedIOException(e);}});if(x[0]>maxFiles||x[1]>maxBytes)throw new IllegalStateException("artifact limits exceeded");}}
    private void copyTree(Path a,Path b)throws IOException{try(var s=Files.walk(a)){s.forEach(p->{Path q=b.resolve(a.relativize(p).toString());try{if(Files.isDirectory(p))Files.createDirectories(q);else {Files.createDirectories(q.getParent()); Files.copy(p,q,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);}}catch(IOException e){throw new UncheckedIOException(e);}});}}
    private String digestTree(Path d)throws Exception{MessageDigest h=MessageDigest.getInstance("SHA-256");try(var s=Files.walk(d).filter(Files::isRegularFile).sorted()){s.forEach(p->{h.update(d.relativize(p).toString().replace('\\','/').getBytes(StandardCharsets.UTF_8));try{h.update(Files.readAllBytes(p));}catch(IOException e){throw new UncheckedIOException(e);}});}return HexFormat.of().formatHex(h.digest());}
    private long treeSize(Path d)throws IOException{try(var s=Files.walk(d).filter(Files::isRegularFile)){return s.mapToLong(p->{try{return Files.size(p);}catch(IOException e){throw new UncheckedIOException(e);}}).sum();}}
    private String manifest(Path d)throws IOException{var out=new StringBuilder();try(var s=Files.walk(d).filter(Files::isRegularFile).sorted()){s.forEach(p->out.append(d.relativize(p).toString().replace((char)92,(char)47)).append((char)10));}return out.toString();}
    private void deleteTree(Path d){try(var s=Files.walk(d).sorted(Comparator.reverseOrder())){s.forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}}); }catch(IOException ignored){}}
}
