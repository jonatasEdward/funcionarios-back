package com.senai.jonatas.funcionarios.service;

import com.senai.jonatas.funcionarios.dto.FuncionarioRequest;
import com.senai.jonatas.funcionarios.dto.FuncionarioResponse;
import com.senai.jonatas.funcionarios.exceptions.*;
import com.senai.jonatas.funcionarios.mapper.FuncionarioMapper;
import com.senai.jonatas.funcionarios.entity.Funcionario;
import com.senai.jonatas.funcionarios.repository.FuncionarioRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class FuncionarioService {

    private final FuncionarioRepository repository;

    public FuncionarioService(FuncionarioRepository repository) {
        this.repository = repository;
    }

    public List<FuncionarioResponse> listar(String cargo, Boolean ativo) {
        List<Funcionario> lista = new ArrayList<>();

        if (cargo != null && !cargo.isBlank() && ativo != null) {
            lista = repository.findByCargoIgnoreCaseContainingAndAtivoOrderByNomeAsc(cargo.trim(), ativo);
        } else if (cargo != null && !cargo.isBlank()) {
            lista = repository.findByCargoIgnoreCaseContainingOrderByNomeAsc(cargo.trim());
        } else if (ativo != null) {
            lista = repository.findByAtivoOrderByNomeAsc(ativo);
        } else {
            lista = repository.findAllByOrderByNomeAsc();
        }

        return lista.stream().map(FuncionarioMapper::toResponse).toList();
    }

    public FuncionarioResponse buscarPorId(Long id) {
        var func = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Funcionário não encontrado"));
        return FuncionarioMapper.toResponse(func);
    }

    @Transactional
    public Result<FuncionarioResponse> cadastrar(FuncionarioRequest req) {
        validarRegrasComuns(req);

        var existenteOpt = repository.findByEmailIgnoreCase(req.email());
        if (existenteOpt.isPresent()) {
            var existente = existenteOpt.get();
            if (Boolean.TRUE.equals(existente.getAtivo())) {
                throw new EmailConflictException("E-mail já cadastrado");
            }
            // Reativar
            aplicarAtualizacao(req, existente, true);
            var salvo = repository.save(existente);
            return Result.reactivated(FuncionarioMapper.toResponse(salvo));
        }

        var novo = FuncionarioMapper.toEntity(req);
        var salvo = repository.save(novo);
        return Result.created(FuncionarioMapper.toResponse(salvo));
    }

    @Transactional
    public FuncionarioResponse atualizar(Long id, FuncionarioRequest req) {
        validarRegrasComuns(req);

        var existente = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Funcionário não encontrado"));

        if (!Boolean.TRUE.equals(existente.getAtivo())) {
            throw new BusinessException("Apenas funcionários ativos podem ser editados");
        }

        // E-mail: não pode virar um valor já existente em outro registro
        if (!existente.getEmail().equalsIgnoreCase(req.email()) &&
            repository.existsByEmailIgnoreCase(req.email())) {
            throw new BusinessException("E-mail informado já está em uso por outro funcionário");
        }

        // Salário não pode reduzir
        if (req.salario().compareTo(existente.getSalario()) < 0) {
            throw new BusinessException("Salário não pode ser reduzido");
        }

        aplicarAtualizacao(req, existente, false);
        var salvo = repository.save(existente);
        return FuncionarioMapper.toResponse(salvo);
    }

    @Transactional
    public FuncionarioResponse inativar(Long id) {
        var existente = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Funcionário não encontrado"));
        existente.setAtivo(false);
        var salvo = repository.save(existente);
        return FuncionarioMapper.toResponse(salvo);
    }

    private void aplicarAtualizacao(FuncionarioRequest req, Funcionario entidade, boolean reativacao) {
        entidade.setNome(req.nome());
        entidade.setCargo(req.cargo());
        entidade.setSalario(req.salario());
        entidade.setDataAdmissao(req.dataAdmissao());
        entidade.setEmail(req.email());
        if (reativacao) {
            entidade.setAtivo(true);
        }
    }

    private void validarRegrasComuns(FuncionarioRequest req) {
        // Nenhum campo só com espaços (Bean Validation + sanity check)
        if (req.nome().isBlank() || req.email().isBlank() || req.cargo().isBlank()) {
            throw new BusinessException("Campos não podem conter apenas espaços em branco");
        }
        // Data não futura (já coberto por @PastOrPresent, reforçando regra de negócio)
        if (req.dataAdmissao().isAfter(LocalDate.now())) {
            throw new BusinessException("Data de admissão não pode ser posterior à data atual");
        }
        // Salário > 0 (já coberto, reforçado)
        if (req.salario() == null || req.salario().signum() <= 0) {
            throw new BusinessException("Salário deve ser maior que zero");
        }
    }

    // Resultado para diferenciar 201 (criado) de 200 (reativado) no controller
    public record Result<T>(T body, boolean created, boolean reactivated) {
        public static <T> Result<T> created(T body) { return new Result<>(body, true, false); }
        public static <T> Result<T> reactivated(T body) { return new Result<>(body, false, true); }
    }
}
