package com.joaoalcantara.triagem.infra;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.joaoalcantara.triagem.triagem.PropriedadesDoWorker;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PropriedadesDoWorker.class)
public class AgendamentoConfig {
}
