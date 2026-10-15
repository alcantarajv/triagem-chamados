-- Como a triagem aconteceu, e nao so o resultado: quais provedores falharam
-- antes do que respondeu, e quanto a rodada demorou. E o que a pagina de
-- demonstracao mostra para tornar visivel o fallback e a latencia do modelo.

ALTER TABLE chamados
    -- Nomes separados por virgula, na ordem da cadeia. Nulo quando o principal
    -- resolveu ou quando a classificacao veio do cache.
    ADD COLUMN provedores_que_falharam VARCHAR(100),
    -- Da reserva ao resultado: o tempo que a triagem levou, sem contar a espera
    -- na fila.
    ADD COLUMN duracao_da_triagem_ms   INTEGER;

ALTER TABLE chamados
    ADD CONSTRAINT chamados_duracao_nao_negativa CHECK (duracao_da_triagem_ms IS NULL OR duracao_da_triagem_ms >= 0);
