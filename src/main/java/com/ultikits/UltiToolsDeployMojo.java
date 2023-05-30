package com.ultikits;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.util.List;

@Mojo(name = "deploy", defaultPhase = LifecyclePhase.DEPLOY)
public class UltiToolsDeployMojo extends AbstractMojo {
    @Parameter(name = "baseDir", defaultValue = "${basedir}")
    private String baseDir;
    @Parameter(name = "version", defaultValue = "${project.version}")
    private String version;
    @Parameter(name = "outputDirectory", defaultValue = "${project.build.directory}")
    private File outputDirectory;
    @Parameter(name = "fileName", defaultValue = "${project.build.finalName}" + ".jar")
    private String fileName;
    @Parameter(name = "accessKeyFile", required = true)
    private String accessKeyFile;
    @Parameter(name = "name", required = true)
    private String name;
    @Parameter(name = "identifyString", required = true)
    private String identifyString;
    @Parameter(name = "shortDescription", defaultValue = "")
    private String shortDescription;

    public void execute() throws MojoExecutionException {
        File f = outputDirectory;

        if (!f.exists()) {
            f.mkdirs();
        }

        File build = new File(f, fileName);
        getLog().info("正在获取访问令牌...");
        File accessFile = new File(baseDir + "/" + accessKeyFile);
        List<String> strings = FileUtil.readUtf8Lines(accessFile);
        if (strings.size() == 0) {
            throw new MojoExecutionException("Empty access key file!");
        }
        String accessKey = strings.get(0);
        getLog().info("成功获取到访问令牌：" + accessKey);
        getLog().info("正在查询远程仓库插件状态..."+identifyString);
        PluginEntity pluginEntity = getPluginEntity(identifyString, version);
        if (pluginEntity == null) {
            getLog().info("未找到插件，正在新建插件模块...");
            if (!isIdStringUsable(identifyString)) {
                throw new MojoExecutionException(String.format("Identify string %s is not usable! Please consider another one!", identifyString));
            }
            getLog().info("正在上传插件...");
            uploadPlugin(accessKey, build);
            return;
        }
        getLog().info("找到插件，正在进行更新...");
        updatePlugin(accessKey, build);
    }

    private boolean isIdStringUsable(String identifyString) {
        return !identifyString.contains("+") &&
                !identifyString.contains("/") &&
                !identifyString.contains(" ") &&
                !identifyString.contains(";") &&
                !identifyString.contains("?") &&
                !identifyString.contains(":") &&
                !identifyString.contains("@") &&
                !identifyString.contains("=") &&
                !identifyString.contains("&");
    }

    private PluginEntity getPluginEntity(String idString, String version) {
        HttpRequest get = HttpUtil.createGet("https://api.ultikits.com/plugin/get?identifyString=" + idString+"&version="+version);
        HttpResponse execute = get.execute();
        getLog().info(execute.body());
        if (execute.getStatus() != 200) {
            return null;
        }
        JSONObject jsonObject = JSONUtil.parseObj(execute.body());
        execute.close();
        return jsonObject.toBean(PluginEntity.class);
    }

    private void uploadPlugin(String accessKey, File file) throws MojoExecutionException {
        HttpRequest post = HttpUtil.createPost("https://api.ultikits.com/developer/plugin/add");
        post.header("Accesskey", accessKey);
        post.form("file", file);
        post.form("shortDescription", shortDescription);
        post.form("identifyString", identifyString);
        post.form("name", name);
        post.form("version", version);
        HttpResponse execute = post.execute();
        if (execute.isOk()) {
            getLog().info("上传成功！");
        } else {
            getLog().error("上传失败！");
            throw new MojoExecutionException(execute.body());
        }
        execute.close();
    }

    private void updatePlugin(String accessKey, File file) throws MojoExecutionException {
        HttpRequest post = HttpUtil.createPost("https://api.ultikits.com/developer/plugin/update");
        post.header("Accesskey", accessKey);
        post.form("file", file);
        post.form("shortDescription", shortDescription);
        post.form("identifyString", identifyString);
        post.form("name", name);
        post.form("version", version);
        HttpResponse execute = post.execute();
        if (execute.isOk()) {
            getLog().info("上传成功！");
        } else {
            getLog().error("上传失败！");
            throw new MojoExecutionException(execute.body());
        }
        execute.close();
    }
}
