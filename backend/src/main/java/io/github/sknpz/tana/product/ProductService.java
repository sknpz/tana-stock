package io.github.sknpz.tana.product;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.sknpz.tana.category.Category;
import io.github.sknpz.tana.category.CategoryService;
import io.github.sknpz.tana.movement.MovementType;
import io.github.sknpz.tana.movement.StockMovementRepository;
import io.github.sknpz.tana.shared.exception.ConflictException;
import io.github.sknpz.tana.shared.csv.CsvWriter;
import io.github.sknpz.tana.shared.exception.ResourceNotFoundException;
import io.github.sknpz.tana.supplier.Supplier;
import io.github.sknpz.tana.supplier.SupplierService;
import io.github.sknpz.tana.user.CurrentUser;

@Service
public class ProductService {

    private static final int DEFAULT_MIN_STOCK = 10;

    private final ProductRepository productRepository;
    private final StockMovementRepository movementRepository;
    private final CategoryService categoryService;
    private final SupplierService supplierService;
    private final CurrentUser currentUser;
    private final ZoneId zoneId;

    public ProductService(ProductRepository productRepository, StockMovementRepository movementRepository,
            CategoryService categoryService, SupplierService supplierService, CurrentUser currentUser,
            @Value("${app.zone-id}") ZoneId zoneId) {
        this.productRepository = productRepository;
        this.movementRepository = movementRepository;
        this.categoryService = categoryService;
        this.supplierService = supplierService;
        this.currentUser = currentUser;
        this.zoneId = zoneId;
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> list(String search, StockStatus status, Long categoryId, Long supplierId,
            Pageable pageable) {
        return productRepository
                .findAll(ProductSpecifications.matching(search, status, categoryId, supplierId), pageable)
                .map(ProductResponse::from);
    }

    /** Escreve o CSV direto na saída, linha a linha, dentro da transação (o stream lê do banco aos poucos). */
    @Transactional(readOnly = true)
    public void exportCsv(String search, StockStatus status, Long categoryId, Long supplierId, OutputStream out) {
        try (CsvWriter csv = CsvWriter.open(out);
                Stream<Product> products = productRepository.streamAll(
                        ProductSpecifications.matching(search, status, categoryId, supplierId),
                        Sort.by("name"))) {
            csv.row("SKU", "Produto", "Categoria", "Fornecedor", "Preço unitário", "Quantidade",
                    "Estoque mínimo", "Valor em estoque", "Situação");
            products.forEach(product -> csv.row(
                    CsvWriter.text(product.getSku()),
                    CsvWriter.text(product.getName()),
                    product.getCategory() == null ? "" : CsvWriter.text(product.getCategory().getName()),
                    product.getSupplier() == null ? "" : CsvWriter.text(product.getSupplier().getName()),
                    CsvWriter.decimal(product.getUnitPrice()),
                    String.valueOf(product.getQuantity()),
                    String.valueOf(product.getMinStock()),
                    CsvWriter.decimal(product.getTotalValue()),
                    statusLabel(product.getStockStatus())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String statusLabel(StockStatus status) {
        return switch (status) {
            case OK -> "OK";
            case LOW_STOCK -> "Estoque baixo";
            case OUT_OF_STOCK -> "Sem estoque";
        };
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Long id) {
        return ProductResponse.from(findActive(id));
    }

    @Transactional
    public ProductResponse create(ProductCreateRequest request) {
        String sku = normalizeSku(request.sku());
        if (sku != null && productRepository.existsBySku(sku)) {
            throw new ConflictException("Já existe um produto com o SKU " + sku + ".");
        }

        Product product = new Product(request.name().trim(), sku, resolveCategory(request.categoryId()),
                resolveSupplier(request.supplierId()), request.unitPrice(),
                request.minStock() != null ? request.minStock() : DEFAULT_MIN_STOCK);
        productRepository.save(product);

        int initialQuantity = request.initialQuantity() != null ? request.initialQuantity() : 0;
        if (initialQuantity > 0) {
            movementRepository.save(product.registerMovement(MovementType.ENTRY, initialQuantity, "Estoque inicial",
                    currentUser.reference().orElse(null)));
        }
        return ProductResponse.from(product);
    }

    @Transactional
    public ProductResponse update(Long id, ProductUpdateRequest request) {
        Product product = findActive(id);
        String sku = normalizeSku(request.sku());
        if (sku != null && productRepository.existsBySkuAndIdNot(sku, id)) {
            throw new ConflictException("Já existe um produto com o SKU " + sku + ".");
        }
        product.update(request.name().trim(), sku, resolveCategory(request.categoryId()),
                resolveSupplier(request.supplierId()), request.unitPrice(), request.minStock());
        return ProductResponse.from(product);
    }

    /** Exclusão lógica: o produto sai das listagens, mas o histórico de movimentações é preservado. */
    @Transactional
    public void delete(Long id) {
        findActive(id).deactivate();
    }

    private Product findActive(Long id) {
        return productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produto", id));
    }

    private Category resolveCategory(Long categoryId) {
        return categoryId == null ? null : categoryService.findById(categoryId);
    }

    private Supplier resolveSupplier(Long supplierId) {
        return supplierId == null ? null : supplierService.findById(supplierId);
    }

    private static String normalizeSku(String sku) {
        return sku == null || sku.isBlank() ? null : sku.trim().toUpperCase();
    }
}
