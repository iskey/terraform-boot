/*
 * SPDX-License-Identifier: Apache-2.0
 * SPDX-FileCopyrightText: Huawei Inc.
 *
 */

package org.eclipse.xpanse.terraform.boot.terraform.service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.xpanse.terraform.boot.models.exceptions.TerraformExecutorException;
import org.eclipse.xpanse.terraform.boot.models.request.TerraformDeployWithScriptsRequest;
import org.eclipse.xpanse.terraform.boot.models.request.TerraformDestroyWithScriptsRequest;
import org.eclipse.xpanse.terraform.boot.models.request.async.TerraformAsyncDeployFromDirectoryRequest;
import org.eclipse.xpanse.terraform.boot.models.request.async.TerraformAsyncDestroyFromDirectoryRequest;
import org.eclipse.xpanse.terraform.boot.models.response.TerraformResult;
import org.eclipse.xpanse.terraform.boot.terraform.TerraformExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

/**
 *  Terraform service classes are deployed form Scripts.
 */
@Slf4j
@Service
public class TerraformScriptsService extends TerraformDirectoryService {

    private static final String FILE_SUFFIX = ".tf";
    private static final String STATE_FILE_NAME = "terraform.tfstate";

    private final RestTemplate restTemplate;
    private final TerraformExecutor executor;

    /**
     * TerraformScriptsService constructor.
     */
    @Autowired
    public TerraformScriptsService(TerraformExecutor executor, RestTemplate restTemplate) {
        super(executor);
        this.executor = executor;
        this.restTemplate = restTemplate;

    }

    /**
     * Method of deployment a service using a script.
     */
    public TerraformResult deployWithScripts(TerraformDeployWithScriptsRequest request) {
        String moduleDirectory = buildDeployEnv(request.getScripts());
        return deployFromDirectory(request, moduleDirectory);
    }

    /**
     * Method of destroy a service using a script.
     */
    public TerraformResult destroyWithScripts(TerraformDestroyWithScriptsRequest request) {
        String moduleDirectory = buildDestroyEnv(request.getScripts(), request.getTfState());
        return destroyFromDirectory(request, moduleDirectory);
    }

    /**
     * Async deploy a source by terraform.
     */
    @Async("taskExecutor")
    public void asyncDeployWithScripts(
            TerraformAsyncDeployFromDirectoryRequest asyncDeployRequest) {
        TerraformResult result = deployWithScripts(asyncDeployRequest);
        log.info("Deployment service complete, {}", result.getCommandStdOutput());
        restTemplate.postForLocation(asyncDeployRequest.getWebhookConfig().getUrl(), result);
    }

    /**
     * Async destroy resource of the service.
     */
    @Async("taskExecutor")
    public void asyncDestroyWithScripts(TerraformAsyncDestroyFromDirectoryRequest request) {
        TerraformResult result = destroyWithScripts(request);
        log.info("Destroy service complete, {}", result.getCommandStdOutput());
        restTemplate.postForLocation(request.getWebhookConfig().getUrl(), result);
    }

    private String buildDeployEnv(List<String> scripts) {
        String moduleDirectory = UUID.randomUUID().toString();
        String workspace = executor.getModuleFullPath(moduleDirectory);
        buildWorkspace(workspace);
        buildScriptFiles(workspace, scripts);
        return moduleDirectory;
    }

    private String buildDestroyEnv(List<String> scripts, String tfState) {
        String moduleDirectory = buildDeployEnv(scripts);
        if (StringUtils.isBlank(tfState)) {
            throw new TerraformExecutorException("terraform .tfState file create error");
        }
        String fileName =
                executor.getModuleFullPath(moduleDirectory) + File.separator + STATE_FILE_NAME;
        try (FileWriter scriptWriter = new FileWriter(fileName)) {
            scriptWriter.write(tfState);
            log.info("terraform .tfState file create success, fileName: {}", fileName);
        } catch (IOException ex) {
            log.error("terraform .tfState file create failed.", ex);
            throw new TerraformExecutorException("terraform .tfState file create failed.", ex);
        }
        return moduleDirectory;
    }

    private void buildWorkspace(String workspace) {
        log.info("start create workspace");
        File ws = new File(workspace);
        if (!ws.exists() && !ws.mkdirs()) {
            throw new TerraformExecutorException(
                    "Create workspace failed, File path not created: " + ws.getAbsolutePath());
        }
        log.info("workspace create success,Working directory is " + ws.getAbsolutePath());
    }

    private void buildScriptFiles(String workspace, List<String> scripts) {
        log.info("start build terraform script");
        if (CollectionUtils.isEmpty(scripts)) {
            throw new TerraformExecutorException("terraform scripts create error, terraform "
                    + "scripts not exists");
        }
        for (String script : scripts) {
            String fileName =
                    workspace + File.separator + UUID.randomUUID() + FILE_SUFFIX;
            try (FileWriter scriptWriter = new FileWriter(fileName)) {
                scriptWriter.write(script);
                log.info("terraform script create success, fileName: {}", fileName);
            } catch (IOException ex) {
                log.error("terraform script create failed.", ex);
                throw new TerraformExecutorException("terraform script create failed.", ex);
            }
        }
    }
}
