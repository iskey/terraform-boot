/*
 * SPDX-License-Identifier: Apache-2.0
 * SPDX-FileCopyrightText: Huawei Inc.
 *
 */

package org.eclipse.xpanse.terraform.boot.terraform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.xpanse.terraform.boot.models.TerraformBootSystemStatus;
import org.eclipse.xpanse.terraform.boot.models.enums.HealthStatus;
import org.eclipse.xpanse.terraform.boot.models.exceptions.TerraformExecutorException;
import org.eclipse.xpanse.terraform.boot.models.exceptions.TerraformHealthCheckException;
import org.eclipse.xpanse.terraform.boot.models.request.TerraformDeployFromDirectoryRequest;
import org.eclipse.xpanse.terraform.boot.models.request.TerraformDestroyFromDirectoryRequest;
import org.eclipse.xpanse.terraform.boot.models.response.TerraformResult;
import org.eclipse.xpanse.terraform.boot.models.validation.TerraformValidationResult;
import org.eclipse.xpanse.terraform.boot.terraform.TerraformExecutor;
import org.eclipse.xpanse.terraform.boot.terraform.utils.SystemCmdResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Terraform service classes are deployed form Directory.
 */
@Slf4j
@Service
public class TerraformDirectoryService {

    private static final String STATE_FILE_NAME = "terraform.tfstate";
    private static final String TEST_FILE_NAME = "hello-world.tf";
    private static final String HEALTH_CHECK_DIR = UUID.randomUUID().toString();
    private static final List<String> EXCLUDED_FILE_SUFFIX_LIST =
            Arrays.asList(".tf", ".tfstate", ".hcl");
    private static final String HELLO_WORLD_TEMPLATE = """
            output "hello_world" {
                value = "Hello, World!"
            }
            """;

    private final TerraformExecutor executor;

    @Autowired
    public TerraformDirectoryService(TerraformExecutor executor) {
        this.executor = executor;
    }

    /**
     * Perform Terraform health checks by creating a Terraform test configuration file.
     *
     * @return TerraformBootSystemStatus.
     */
    public TerraformBootSystemStatus tfHealthCheck() {
        String filePath = executor.getModuleFullPath(HEALTH_CHECK_DIR) + File.separator
                + TEST_FILE_NAME;
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                file.getParentFile().mkdirs();
                file.createNewFile();
            }
            FileWriter writer = new FileWriter(filePath);
            writer.write(HELLO_WORLD_TEMPLATE);
            writer.close();
        } catch (IOException e) {
            throw new TerraformHealthCheckException(
                    "Error creating or writing to file '" + filePath + "': " + e.getMessage());
        }
        TerraformValidationResult terraformValidationResult =
                tfValidateFromDirectory(HEALTH_CHECK_DIR);
        TerraformBootSystemStatus systemStatus = new TerraformBootSystemStatus();
        if (terraformValidationResult.isValid()) {
            systemStatus.setHealthStatus(HealthStatus.OK);
            return systemStatus;
        }
        systemStatus.setHealthStatus(HealthStatus.NOK);
        return systemStatus;
    }

    /**
     * Executes terraform validate command.
     *
     * @return TfValidationResult.
     */
    public TerraformValidationResult tfValidateFromDirectory(String moduleDirectory) {
        try {
            SystemCmdResult systemCmdResult = executor.tfValidate(moduleDirectory);
            return new ObjectMapper().readValue(systemCmdResult.getCommandStdOutput(),
                    TerraformValidationResult.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Serialising string to object failed.", ex);
        }
    }

    /**
     * Deploy a source by terraform.
     */
    public TerraformResult deployFromDirectory(TerraformDeployFromDirectoryRequest request,
            String moduleDirectory) {
        SystemCmdResult result;
        if (Boolean.TRUE.equals(request.getIsPlanOnly())) {
            result = executor.tfPlan(request.getVariables(), request.getEnvVariables(),
                    moduleDirectory);
        } else {
            result = executor.tfApply(request.getVariables(), request.getEnvVariables(),
                    moduleDirectory);
            if (!result.isCommandSuccessful()) {
                log.error("TFExecutor.tfApply failed.");
                throw new TerraformExecutorException("TFExecutor.tfApply failed.",
                        result.getCommandStdError());
            }
        }
        String workspace = executor.getModuleFullPath(moduleDirectory);
        TerraformResult build = TerraformResult.builder()
                .commandStdOutput(result.getCommandStdOutput())
                .commandStdError(result.getCommandStdError())
                .isCommandSuccessful(result.isCommandSuccessful())
                .terraformState(getTerraformState(workspace))
                .importantFileContentMap(
                        getImportantFilesContent(workspace))
                .build();
        deleteWorkspace(workspace);
        return build;
    }

    /**
     * Destroy resource of the service.
     */
    public TerraformResult destroyFromDirectory(TerraformDestroyFromDirectoryRequest request,
            String moduleDirectory) {
        SystemCmdResult result = executor.tfDestroy(request.getVariables(),
                request.getEnvVariables(), moduleDirectory);
        if (!result.isCommandSuccessful()) {
            log.error("TFExecutor.tfDestroy failed.");
            throw new TerraformExecutorException("TFExecutor.tfDestroy failed.",
                    result.getCommandStdError());
        }
        String workspace = executor.getModuleFullPath(moduleDirectory);
        TerraformResult build = TerraformResult.builder()
                .commandStdOutput(result.getCommandStdOutput())
                .commandStdError(result.getCommandStdError())
                .isCommandSuccessful(result.isCommandSuccessful())
                .terraformState(getTerraformState(workspace))
                .importantFileContentMap(
                        getImportantFilesContent(workspace))
                .build();
        deleteWorkspace(workspace);
        return build;

    }

    /**
     * Get the content of the tfState file.
     */
    public String getTerraformState(String workspace) {
        File tfState = new File(workspace + File.separator + STATE_FILE_NAME);
        if (!tfState.exists()) {
            log.info("Terraform state file not exists.");
            return null;
        }
        try {
            return Files.readString(tfState.toPath());
        } catch (IOException ex) {
            throw new TerraformExecutorException("Read state file failed.", ex);
        }
    }

    /**
     * get file content.
     */
    public Map<String, String> getImportantFilesContent(String workspace) {
        Map<String, String> fileContentMap = new HashMap<>();
        File workPath = new File(workspace);
        if (workPath.isDirectory() && workPath.exists()) {
            File[] files = workPath.listFiles();
            if (Objects.nonNull(files)) {
                List<File> importantFiles = Arrays.stream(files)
                        .filter(file -> file.isFile() && !isExcludedFile(file.getName())).toList();
                for (File importantFile : importantFiles) {
                    try {
                        String content = Files.readString(importantFile.toPath());
                        fileContentMap.put(importantFile.getName(), content);
                    } catch (IOException e) {
                        log.error("Read content of file with name:{} error.",
                                importantFile.getName(), e);
                    }
                }
            }
        }
        return fileContentMap;
    }

    private void deleteWorkspace(String workspace) {
        Path path = Paths.get(workspace);
        try {
            Files.walk(path).sorted(Comparator.reverseOrder()).map(Path::toFile)
                    .forEach(File::delete);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean isExcludedFile(String fileName) {
        String fileSuffix = fileName.substring(fileName.lastIndexOf("."));
        return EXCLUDED_FILE_SUFFIX_LIST.contains(fileSuffix);
    }
}
