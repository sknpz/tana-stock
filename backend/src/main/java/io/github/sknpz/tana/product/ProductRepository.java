package io.github.sknpz.tana.product;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    // EntityGraph carrega categoria e fornecedor no mesmo SELECT (evita o problema N+1)
    @Override
    @EntityGraph(attributePaths = {"category", "supplier"})
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    /**
     * Produtos filtrados em fluxo, para a exportação. O stream precisa ser consumido (e fechado)
     * dentro da transação; categoria e fornecedor vêm no mesmo SELECT.
     */
    default Stream<Product> streamAll(Specification<Product> spec, Sort sort) {
        return findBy(spec, query -> query.project("category", "supplier").sortBy(sort).stream());
    }

    @EntityGraph(attributePaths = {"category", "supplier"})
    Optional<Product> findByIdAndActiveTrue(Long id);

    /**
     * SELECT ... FOR UPDATE: trava a linha do produto até o fim da transação, para que duas
     * movimentações simultâneas não leiam o mesmo saldo e uma sobrescreva a outra.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id and p.active = true")
    Optional<Product> findActiveByIdForUpdate(@Param("id") Long id);

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, Long id);

    boolean existsBySupplierId(Long supplierId);

    long countBySupplierIdAndActiveTrue(Long supplierId);

    @Query("""
            select p.supplier.id as supplierId, count(p) as total
            from Product p
            where p.active = true and p.supplier is not null
            group by p.supplier.id
            """)
    List<SupplierProductCount> countActiveGroupedBySupplier();

    default Map<Long, Long> countActiveProductsBySupplier() {
        return countActiveGroupedBySupplier().stream()
                .collect(Collectors.toMap(SupplierProductCount::getSupplierId, SupplierProductCount::getTotal));
    }

    interface SupplierProductCount {
        Long getSupplierId();

        Long getTotal();
    }
}
