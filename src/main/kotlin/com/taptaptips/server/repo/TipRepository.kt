package com.taptaptips.server.repo

import com.taptaptips.server.domain.Tip
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface TipRepository : JpaRepository<Tip, UUID>
