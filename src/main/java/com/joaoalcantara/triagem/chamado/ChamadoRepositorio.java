package com.joaoalcantara.triagem.chamado;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChamadoRepositorio extends JpaRepository<Chamado, UUID> {
}
