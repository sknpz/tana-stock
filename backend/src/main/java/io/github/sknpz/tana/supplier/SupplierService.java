package io.github.sknpz.tana.supplier;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.sknpz.tana.product.ProductRepository;
import io.github.sknpz.tana.shared.csv.CsvWriter;
import io.github.sknpz.tana.shared.exception.ConflictException;
import io.github.sknpz.tana.shared.exception.ResourceNotFoundException;
import io.github.sknpz.tana.shared.validation.Cnpj;

@Service
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;

    public SupplierService(SupplierRepository supplierRepository, ProductRepository productRepository) {
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<SupplierResponse> list(String search) {
        String term = blankToNull(search);
        Map<Long, Long> productCounts = productRepository.countActiveProductsBySupplier();
        // Sem busca, consulta separada: um parâmetro nulo no LIKE chega ao PostgreSQL sem tipo definido
        List<Supplier> suppliers = term == null
                ? supplierRepository.findAllByOrderByNameAsc()
                : supplierRepository.search(term);
        return suppliers.stream()
                .map(s -> SupplierResponse.from(s, productCounts.getOrDefault(s.getId(), 0L)))
                .toList();
    }

    /** Escreve o CSV dos fornecedores filtrados; a lista é pequena e já vem carregada por {@link #list}. */
    @Transactional(readOnly = true)
    public void exportCsv(String search, OutputStream out) {
        try (CsvWriter csv = CsvWriter.open(out)) {
            csv.row("Nome", "CNPJ", "Telefone", "E-mail", "Endereço", "Produtos vinculados");
            for (SupplierResponse supplier : list(search)) {
                csv.row(CsvWriter.text(supplier.name()),
                        Cnpj.format(supplier.cnpj()),
                        CsvWriter.text(supplier.phone()),
                        CsvWriter.text(supplier.email()),
                        CsvWriter.text(supplier.address()),
                        String.valueOf(supplier.activeProducts()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Transactional(readOnly = true)
    public SupplierResponse get(Long id) {
        Supplier supplier = findById(id);
        return SupplierResponse.from(supplier, productRepository.countBySupplierIdAndActiveTrue(id));
    }

    @Transactional
    public SupplierResponse create(SupplierRequest request) {
        String cnpj = Cnpj.normalize(request.cnpj());
        if (cnpj != null && supplierRepository.existsByCnpj(cnpj)) {
            throw new ConflictException("Já existe um fornecedor com o CNPJ " + cnpj + ".");
        }
        Supplier supplier = new Supplier(request.name().trim(), cnpj, blankToNull(request.phone()),
                blankToNull(request.email()), blankToNull(request.address()));
        return SupplierResponse.from(supplierRepository.save(supplier), 0);
    }

    @Transactional
    public SupplierResponse update(Long id, SupplierRequest request) {
        Supplier supplier = findById(id);
        String cnpj = Cnpj.normalize(request.cnpj());
        if (cnpj != null && supplierRepository.existsByCnpjAndIdNot(cnpj, id)) {
            throw new ConflictException("Já existe um fornecedor com o CNPJ " + cnpj + ".");
        }
        supplier.update(request.name().trim(), cnpj, blankToNull(request.phone()),
                blankToNull(request.email()), blankToNull(request.address()));
        return SupplierResponse.from(supplier, productRepository.countBySupplierIdAndActiveTrue(id));
    }

    @Transactional
    public void delete(Long id) {
        Supplier supplier = findById(id);
        // Inclui produtos inativos: eles continuam ligados ao fornecedor no histórico
        if (productRepository.existsBySupplierId(id)) {
            throw new ConflictException(
                    "O fornecedor possui produtos vinculados (inclusive excluídos, mantidos no histórico) e não pode ser excluído.");
        }
        supplierRepository.delete(supplier);
    }

    /** Usado por outros módulos para resolver o fornecedor informado em um produto. */
    @Transactional(readOnly = true)
    public Supplier findById(Long id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor", id));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
