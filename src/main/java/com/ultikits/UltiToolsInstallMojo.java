package com.ultikits;

import cn.hutool.core.io.FileUtil;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

@Mojo(name = "install", defaultPhase = LifecyclePhase.INSTALL)
public class UltiToolsInstallMojo extends AbstractMojo {
    @Parameter(name = "outputDirectory", defaultValue = "${project.build.directory}")
    private File outputDirectory;
    @Parameter(name = "fileName", defaultValue = "${project.build.finalName}" + ".jar")
    private String fileName;
    @Parameter(name = "pluginFolder")
    private String pluginFolder;

    public void execute() throws MojoExecutionException {
        if (pluginFolder == null) {
            return;
        }
        File f = outputDirectory;
        if (!f.exists()) {
            f.mkdirs();
        }
        File build = new File(f, fileName);
        getLog().info("正在复制构建结果...");
        File dest = new File(pluginFolder, fileName);
        FileUtil.copy(build, dest, true);
        getLog().info("复制完毕！");
    }
}
