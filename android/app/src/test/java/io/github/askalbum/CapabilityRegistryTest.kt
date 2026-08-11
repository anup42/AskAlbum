package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRegistryTest {
    @Test
    fun everyPlannerVisibleIntentHasARegisteredExecutorAndAnswer() {
        assertEquals(QueryIntent.entries.toSet(), CapabilityRegistry.descriptors.map { it.intent }.toSet())
        QueryIntent.entries.forEach { intent ->
            val answer = CapabilityAnswerExecutor.execute(context(intent))
            assertTrue("$intent returned an empty headline", answer.headline.isNotBlank())
            assertTrue(CapabilityRegistry.requireExecutable(intent).executorId.isNotBlank())
        }
    }

    @Test
    fun findMediaWordingDistinguishesBoundedRetrievalFromCompleteCoverage() {
        val base = context(QueryIntent.FIND_MEDIA)

        val estimated = CapabilityAnswerExecutor.execute(
            base.copy(exactness = ResultExactness.ESTIMATED_FROM_RETRIEVAL),
        )
        val exact = CapabilityAnswerExecutor.execute(
            base.copy(exactness = ResultExactness.EXACT),
        )

        assertTrue(estimated.headline.contains("likely matches"))
        assertTrue(estimated.headline.contains("retrieval pass"))
        assertTrue(exact.headline.contains("matching items"))
        assertFalse(exact.headline.contains("retrieval pass"))
    }

    @Test
    fun documentAllowlistContainsEveryRequiredField() {
        assertEquals(
            setOf("total", "amount", "password", "flight_number", "flight_time", "order_id", "email", "phone", "date", "url", "merchant"),
            OcrFactAllowlist.fields.mapTo(mutableSetOf()) { it.key },
        )
        assertEquals(OcrEntityType.RECEIPT_TOTAL, OcrFactAllowlist.resolve("amount paid")?.type)
        assertEquals(OcrEntityType.AMOUNT, OcrFactAllowlist.resolve("amount")?.type)
        assertEquals("flight_number", OcrFactAllowlist.resolve("document_flight_number")?.key)
        assertEquals("password", OcrFactAllowlist.resolve("DOCUMENT-PASSWORD")?.key)
    }

    @Test
    fun everyAllowlistedDocumentFieldHasADeterministicExecutor() {
        OcrFactAllowlist.fields.forEach { field ->
            val value = when (field.key) {
                "total" -> "INR 1248.00"
                "amount" -> "INR 42.00"
                "password" -> "mango-tree-2048"
                "flight_number" -> "AI 302"
                "flight_time" -> "10:45 PM"
                "order_id" -> "ORDER-42"
                "email" -> "fixture@example.test"
                "phone" -> "+91 90000 00000"
                "date" -> "2026-08-11"
                "url" -> "https://example.test/order/42"
                else -> "Swiggy"
            }
            val document = hit("field-${field.key}", "Document", "INR 1.00", 1_700_000_000_000).copy(
                evidence = listOf(
                    EvidenceRecord("evidence-${field.key}", "field-${field.key}", field.sourceField, value, .99f),
                ),
            )
            val base = context(QueryIntent.ANSWER_FACT)
            val answer = CapabilityAnswerExecutor.execute(
                base.copy(
                    plan = base.plan.copy(ocrClause = OcrClause(requestedField = field.key)),
                    hits = listOf(document),
                    deterministicHits = listOf(document),
                    matchCount = 1,
                    indexedEligibleCount = 1,
                    totalEligibleCount = 1,
                    sensitiveContentAuthorized = true,
                ),
            )

            assertEquals(field.key, value, answer.headline)
            assertEquals(field.key, listOf("evidence-${field.key}"), answer.evidenceIds)
        }
    }

    @Test
    fun advertisedGenericDocumentQaReturnsAllAllowlistedDetails() {
        val descriptor = CapabilityRegistry.descriptors.single { it.intent == QueryIntent.DOCUMENT_QA }
        val plan = QueryCompiler().compile(descriptor.suggestedQuery)
        assertEquals(QueryIntent.DOCUMENT_QA, plan.intent)
        assertEquals(null, plan.ocrClause?.requestedField)
        val document = hit("boarding-pass", "Boarding pass", "INR 1.00", 1_700_000_000_000).copy(
            evidence = listOf(
                EvidenceRecord("flight-number", "boarding-pass", "document_flight_number", "AI 302", .99f),
                EvidenceRecord("flight-time", "boarding-pass", "document_flight_time", "10:45 PM", .98f),
                EvidenceRecord("merchant", "boarding-pass", "document_merchant", "Air India", .97f),
            ),
        )
        val base = context(QueryIntent.DOCUMENT_QA)
        val answer = CapabilityAnswerExecutor.execute(
            base.copy(
                plan = plan,
                hits = listOf(document),
                deterministicHits = listOf(document),
                matchCount = 1,
                indexedEligibleCount = 1,
                totalEligibleCount = 1,
            ),
        )

        assertEquals("3 document details", answer.headline)
        assertTrue(answer.detail.contains("flight number: AI 302"))
        assertTrue(answer.detail.contains("flight time: 10:45 PM"))
        assertTrue(answer.detail.contains("merchant: Air India"))
        assertEquals(setOf("flight-number", "flight-time", "merchant"), answer.evidenceIds.toSet())
        assertTrue(hasDeterministicDocumentAnswer(plan, listOf(document), true, false))
        assertFalse(hasDeterministicDocumentAnswer(plan, listOf(document), false, false))
        assertFalse(hasDeterministicDocumentAnswer(plan, listOf(document), true, true))
    }

    @Test
    fun unsupportedDocumentFieldCannotBecomeADeterministicGenericAnswer() {
        val plan = GalleryQueryPlan(
            originalQuery = "What is the bank account in this document?",
            intent = QueryIntent.DOCUMENT_QA,
            ocrClause = OcrClause(requestedField = "bank_account"),
        )
        val document = hit("unsupported-field", "Document", "INR 1.00", 1_700_000_000_000)

        assertFalse(hasDeterministicDocumentAnswer(plan, listOf(document), true, false))
    }

    @Test
    fun genericDocumentQaIsLockedBeforeAnySensitiveDetailCanBeRenderedOrComposed() {
        val plan = QueryCompiler().compile("What details are in my latest document?")
        val document = hit("private-document", "Private document", "INR 1.00", 1_700_000_000_000).copy(
            evidence = listOf(
                EvidenceRecord("flight-number", "private-document", "document_flight_number", "AI 302", .99f),
                EvidenceRecord("password", "private-document", "document_password", "mango-tree-2048", .99f),
            ),
        )
        val base = context(QueryIntent.DOCUMENT_QA).copy(
            plan = plan,
            hits = listOf(document),
            deterministicHits = listOf(document),
            matchCount = 1,
            indexedEligibleCount = 1,
            totalEligibleCount = 1,
        )

        assertTrue(requiresAuthenticationForAnswer(plan, listOf(document), listOf(document)))
        val locked = CapabilityAnswerExecutor.execute(base)
        assertTrue(locked.requiresAuthentication)
        assertEquals(SensitiveEvidencePolicy.LOCKED_HEADLINE, locked.headline)
        assertTrue(locked.evidenceIds.isEmpty())
        assertFalse(locked.detail.contains("mango-tree-2048"))

        val authorized = CapabilityAnswerExecutor.execute(base.copy(sensitiveContentAuthorized = true))
        assertFalse(authorized.requiresAuthentication)
        assertTrue(authorized.detail.contains("password: mango-tree-2048"))
        assertEquals(setOf("flight-number", "password"), authorized.evidenceIds.toSet())
    }

    @Test
    fun validatorRejectsUnknownOcrFieldsBeforeExecution() {
        val plan = QueryCompiler().compile("What is the password in the latest screenshot?").copy(
            ocrClause = OcrClause(requestedField = "not_allowlisted"),
        )

        val result = GalleryQueryPlanValidator().validate(plan)

        assertTrue(result.errors.contains("Unsupported OCR field"))
    }

    @Test
    fun sumAndMinMaxUseDeterministicEvidenceAndRejectMixedCurrencies() {
        val sum = CapabilityAnswerExecutor.execute(context(QueryIntent.SUM))
        val minMax = CapabilityAnswerExecutor.execute(context(QueryIntent.MIN_MAX))
        val mixed = CapabilityAnswerExecutor.execute(
            context(QueryIntent.SUM).copy(
                hits = listOf(
                    hit("one", "Trip A", "INR 10.00", 1_700_000_000_000),
                    hit("two", "Trip B", "USD 20.00", 1_710_000_000_000),
                ),
            ),
        )

        assertEquals("INR 30", sum.headline)
        assertTrue(minMax.detail.contains("Minimum:"))
        assertEquals("Mixed currencies were not summed", mixed.headline)
    }

    @Test
    fun asciiCurrencyFormsAndSeparatorsRemainReadable() {
        val rupee = CapabilityAnswerExecutor.execute(
            context(QueryIntent.SUM).copy(
                hits = listOf(hit("rupee", "Trip", "Rs. 10.00", 1_700_000_000_000)),
                deterministicHits = listOf(hit("rupee", "Trip", "Rs. 10.00", 1_700_000_000_000)),
            ),
        )
        val mixed = CapabilityAnswerExecutor.execute(
            context(QueryIntent.SUM).copy(
                hits = listOf(
                    hit("inr", "Trip A", "INR 10.00", 1_700_000_000_000),
                    hit("usd", "Trip B", "USD 20.00", 1_710_000_000_000),
                ),
            ),
        )

        assertEquals("INR 10", rupee.headline)
        assertTrue(mixed.detail.contains("; "))
        assertFalse(mixed.detail.contains("•"))
    }

    @Test
    fun aggregationUsesCompleteDeterministicEvidenceInsteadOfRankedTopK() {
        val complete = hit("three", "Trip C", "INR 30.00", 1_720_000_000_000)
        val context = context(QueryIntent.SUM).copy(deterministicHits = context(QueryIntent.SUM).hits + complete)

        assertEquals("INR 60", CapabilityAnswerExecutor.execute(context).headline)
    }

    @Test
    fun boundedAggregationDoesNotPresentAnExactNumericAnswer() {
        val partial = context(QueryIntent.SUM).copy(
            exactness = ResultExactness.ESTIMATED_FROM_RETRIEVAL,
            indexedEligibleCount = 2,
            totalEligibleCount = 10,
            deterministicHits = emptyList(),
        )
        val sum = CapabilityAnswerExecutor.execute(partial)
        val minMax = CapabilityAnswerExecutor.execute(
            partial.copy(plan = partial.plan.copy(intent = QueryIntent.MIN_MAX))
        )

        assertEquals("Exact sum unavailable", sum.headline)
        assertEquals("Exact minimum or maximum unavailable", minMax.headline)
        assertTrue(sum.detail.contains("partial", ignoreCase = true))
        assertTrue(minMax.detail.contains("partial", ignoreCase = true))
    }

    @Test
    fun boundedDocumentFactDoesNotReturnAValueFromPartialOcrCoverage() {
        val partial = context(QueryIntent.DOCUMENT_QA).copy(
            exactness = ResultExactness.ESTIMATED_FROM_RETRIEVAL,
            indexedEligibleCount = 2,
            totalEligibleCount = 10,
        )

        val answer = CapabilityAnswerExecutor.execute(partial)

        assertEquals("Document fact unavailable", answer.headline)
        assertTrue(answer.detail.contains("partial OCR", ignoreCase = true))
        assertFalse(answer.headline.contains("INR", ignoreCase = true))
    }

    @Test
    fun listUsesCompleteDeterministicEvidenceInsteadOfRankedTopK() {
        val complete = hit("three", "Trip C", "INR 30.00", 1_720_000_000_000)
        val base = context(QueryIntent.LIST)
        val answer = CapabilityAnswerExecutor.execute(
            base.copy(deterministicHits = base.hits + complete),
        )

        assertTrue(answer.headline.contains("places"))
        assertTrue(answer.detail.contains("Trip C"))
    }

    @Test
    fun eventSummaryAndTimelineUseCompleteResolvedScope() {
        val base = context(QueryIntent.EVENT_SUMMARY)
        val complete = hit("three", "Trip C", "INR 30.00", 1_720_000_000_000)
        val completeEvent = event(3, "Trip C")
        val summary = CapabilityAnswerExecutor.execute(
            base.copy(
                hits = base.hits.take(1),
                deterministicHits = listOf(complete),
                eventsByMedia = mapOf("three" to completeEvent),
                eventCoverageComplete = true,
            ),
        )
        val timeline = CapabilityAnswerExecutor.execute(
            context(QueryIntent.TIMELINE).copy(
                hits = base.hits.take(1),
                deterministicHits = listOf(complete),
                eventCoverageComplete = true,
            ),
        )

        assertEquals("Trip C", summary.headline)
        assertTrue(summary.detail.contains("complete", ignoreCase = true))
        assertTrue(timeline.detail.contains("2024-07-03"))
        assertTrue(timeline.detail.contains("Complete dates", ignoreCase = true))
    }

    @Test
    fun boundedEventAnswersDoNotClaimCompleteMembership() {
        val boundedSummary = CapabilityAnswerExecutor.execute(
            context(QueryIntent.EVENT_SUMMARY).copy(
                exactness = ResultExactness.ESTIMATED_FROM_RETRIEVAL,
                deterministicHits = listOf(context(QueryIntent.EVENT_SUMMARY).hits.first()),
            ),
        )
        val boundedTimeline = CapabilityAnswerExecutor.execute(
            context(QueryIntent.TIMELINE).copy(
                exactness = ResultExactness.PARTIAL_INDEX,
                deterministicHits = listOf(context(QueryIntent.TIMELINE).hits.first()),
            ),
        )
        val boundedCompare = CapabilityAnswerExecutor.execute(
            context(QueryIntent.COMPARE).copy(
                exactness = ResultExactness.ESTIMATED_FROM_RETRIEVAL,
                deterministicHits = context(QueryIntent.COMPARE).hits,
                comparisonScopes = listOf("Trip A", "Trip B"),
            ),
        )

        assertTrue(boundedSummary.detail.contains("current ranked retrieval pass", ignoreCase = true))
        assertFalse(boundedSummary.detail.contains("evaluated completely", ignoreCase = true))
        assertTrue(boundedTimeline.detail.contains("limited to the current retrieval pass", ignoreCase = true))
        assertFalse(boundedCompare.detail.contains("Complete eligible membership", ignoreCase = true))
    }

    @Test
    fun minAndMaxRespectTheRequestedOperation() {
        val base = context(QueryIntent.MIN_MAX)
        val minimum = CapabilityAnswerExecutor.execute(
            base.copy(plan = base.plan.copy(aggregation = AggregationSpec(AggregationOperation.MIN, "total"))),
        )
        val maximum = CapabilityAnswerExecutor.execute(
            base.copy(plan = base.plan.copy(aggregation = AggregationSpec(AggregationOperation.MAX, "total"))),
        )

        assertEquals("INR 10", minimum.headline)
        assertEquals("INR 20", maximum.headline)
        assertEquals(2, minimum.evidenceIds.size)
        assertEquals(2, maximum.evidenceIds.size)
    }

    @Test
    fun passwordEvidenceAlwaysRequiresAuthentication() {
        val password = hit("secret", "Wi-Fi", "INR 10.00", 1_700_000_000_000).copy(
            evidence = listOf(EvidenceRecord("secret:password", "secret", "document_password", "mango-tree-2048", .95f)),
        )

        assertTrue(SensitiveEvidencePolicy.requiresAuthentication(password))
    }

    @Test
    fun capabilityExecutorLocksSensitiveValuesEvenWhenCalledDirectly() {
        val password = hit("secret", "Wi-Fi", "INR 10.00", 1_700_000_000_000).copy(
            evidence = listOf(EvidenceRecord("secret:password", "secret", "document_password", "mango-tree-2048", .95f)),
        )
        val answer = CapabilityAnswerExecutor.execute(
            context(QueryIntent.LIST).copy(
                hits = listOf(password),
                deterministicHits = listOf(password),
                plan = context(QueryIntent.LIST).plan.copy(
                    ocrClause = OcrClause(requestedField = "password"),
                ),
            ),
        )

        assertEquals(SensitiveEvidencePolicy.LOCKED_HEADLINE, answer.headline)
        assertTrue(answer.requiresAuthentication)
        assertFalse(answer.detail.contains("mango-tree-2048"))
    }

    @Test
    fun directAggregationExecutorLocksSensitiveFinancialValues() {
        val unauthorizedSum = CapabilityAnswerExecutor.execute(
            context(QueryIntent.SUM).copy(sensitiveContentAuthorized = false),
        )
        val unauthorizedMinMax = CapabilityAnswerExecutor.execute(
            context(QueryIntent.MIN_MAX).copy(sensitiveContentAuthorized = false),
        )

        listOf(unauthorizedSum, unauthorizedMinMax).forEach { answer ->
            assertEquals(SensitiveEvidencePolicy.LOCKED_HEADLINE, answer.headline)
            assertTrue(answer.requiresAuthentication)
            assertTrue(answer.evidenceIds.isEmpty())
            assertFalse(answer.detail.contains("INR"))
        }
    }

    @Test
    fun deterministicAnswerEvidenceAlsoRequiresAuthentication() {
        val password = hit("secret", "Wi-Fi", "INR 10.00", 1_700_000_000_000).copy(
            evidence = listOf(EvidenceRecord("secret:password", "secret", "document_password", "mango-tree-2048", .95f)),
        )

        val plan = GalleryQueryPlan(
            originalQuery = "What is the Wi-Fi password?",
            intent = QueryIntent.ANSWER_FACT,
            ocrClause = OcrClause(requestedField = "password"),
        )

        assertTrue(requiresAuthenticationForAnswer(plan, emptyList(), listOf(password)))
    }

    @Test
    fun unrelatedSensitiveEvidenceDoesNotLockOrdinaryMediaAnswer() {
        val password = hit("secret", "Wi-Fi", "INR 10.00", 1_700_000_000_000).copy(
            evidence = listOf(EvidenceRecord("secret:password", "secret", "document_password", "mango-tree-2048", .95f)),
        )
        val ordinary = GalleryQueryPlan(
            originalQuery = "Show screenshots",
            intent = QueryIntent.FIND_MEDIA,
            terms = listOf("screenshot"),
        )
        val differentProtectedField = GalleryQueryPlan(
            originalQuery = "What is the latest phone number?",
            intent = QueryIntent.ANSWER_FACT,
            ocrClause = OcrClause(requestedField = "phone"),
        )

        assertFalse(requiresAuthenticationForAnswer(ordinary, listOf(password), emptyList()))
        assertFalse(requiresAuthenticationForAnswer(differentProtectedField, listOf(password), emptyList()))
    }

    @Test
    fun sensitiveAggregationStillRequiresAuthentication() {
        val receipt = hit("receipt", "Swiggy", "INR 1,248.00", 1_700_000_000_000).copy(
            evidence = listOf(EvidenceRecord("receipt:total", "receipt", "document_total", "INR 1,248.00", .95f)),
        )
        val plan = GalleryQueryPlan(
            originalQuery = "Sum my receipt totals",
            intent = QueryIntent.SUM,
            aggregation = AggregationSpec(AggregationOperation.SUM, "total"),
        )

        assertTrue(requiresAuthenticationForAnswer(plan, emptyList(), listOf(receipt)))
    }

    @Test
    fun financialEvidenceAlwaysRequiresAuthentication() {
        val receipt = hit("receipt", "Swiggy", "INR 1,248.00", 1_700_000_000_000).copy(
            evidence = listOf(EvidenceRecord("receipt:total", "receipt", "document_total", "INR 1,248.00", .95f)),
        )

        assertTrue(SensitiveEvidencePolicy.requiresAuthentication(receipt))
    }

    @Test
    fun flightTimeExtractionIsAllowlisted() {
        val entities = DocumentFactExtractor.extract(
            listOf(OcrBlockRecord("Departure time: 10:45 PM", confidence = .95f, left = 0f, top = .2f, right = 1f, bottom = .3f)),
        )

        assertTrue(entities.any { it.type == OcrEntityType.FLIGHT_TIME && it.normalizedValue == "10:45 PM" })
    }

    @Test
    fun offlineCompilerCanReachAggregationAndComparisonExecutors() {
        assertEquals(QueryIntent.SUM, QueryCompiler().compile("Sum my receipt totals").intent)
        val maximum = QueryCompiler().compile("Which receipt has the highest total?")
        assertEquals(QueryIntent.MIN_MAX, maximum.intent)
        assertEquals(AggregationOperation.MAX, maximum.aggregation?.operation)
        assertEquals(null, maximum.ocrClause?.merchant)
        assertEquals(QueryIntent.COMPARE, QueryCompiler().compile("Compare Goa versus Singapore").intent)
        assertEquals(QueryIntent.TIMELINE, QueryCompiler().compile("Timeline of Singapore photos").intent)
        assertEquals(QueryIntent.LIST, QueryCompiler().compile("List places in recent photos").intent)
    }

    @Test
    fun everyAdvertisedSuggestionRoutesToItsRegisteredIntent() {
        CapabilityRegistry.descriptors.forEach { descriptor ->
            assertEquals(
                descriptor.suggestedQuery,
                descriptor.intent,
                QueryCompiler().compile(descriptor.suggestedQuery).intent,
            )
        }
    }

    @Test
    fun compilerDistinguishesGenericAmountFromReceiptTotal() {
        val amount = QueryCompiler().compile("What is the amount on my latest receipt?")
        val total = QueryCompiler().compile("What was the total on my latest receipt?")

        assertEquals(QueryIntent.ANSWER_FACT, amount.intent)
        assertEquals("amount", amount.ocrClause?.requestedField)
        assertEquals("total", total.ocrClause?.requestedField)
    }

    @Test
    fun unsupportedDocumentFieldsNeverFallbackToReceiptTotal() {
        val fact = CapabilityAnswerExecutor.execute(
            context(QueryIntent.DOCUMENT_QA).copy(
                plan = context(QueryIntent.DOCUMENT_QA).plan.copy(
                    ocrClause = OcrClause(requestedField = "bank_account"),
                ),
            ),
        )
        val sum = CapabilityAnswerExecutor.execute(
            context(QueryIntent.SUM).copy(
                plan = context(QueryIntent.SUM).plan.copy(
                    aggregation = AggregationSpec(AggregationOperation.SUM, "bank_account"),
                ),
            ),
        )
        val minMax = CapabilityAnswerExecutor.execute(
            context(QueryIntent.MIN_MAX).copy(
                plan = context(QueryIntent.MIN_MAX).plan.copy(
                    aggregation = AggregationSpec(AggregationOperation.MIN_MAX, "bank_account"),
                ),
            ),
        )

        assertEquals("Unsupported document field", fact.headline)
        assertEquals("Unsupported document field", sum.headline)
        assertEquals("Unsupported document field", minMax.headline)
        assertFalse(fact.headline.contains("INR"))
    }

    @Test
    fun compareExecutorUsesBothExplicitScopes() {
        val base = context(QueryIntent.COMPARE)
        val goa = hit("goa", "Goa", "INR 10.00", 1_700_000_000_000).let {
            it.copy(item = it.item.copy(location = "Goa"))
        }
        val singapore = hit("singapore", "Singapore", "INR 20.00", 1_710_000_000_000).let {
            it.copy(item = it.item.copy(location = "Singapore"))
        }

        val answer = CapabilityAnswerExecutor.execute(
            base.copy(
                hits = listOf(goa, singapore),
                deterministicHits = listOf(goa, singapore),
                comparisonScopes = listOf("goa", "singapore"),
            ),
        )

        assertTrue(answer.headline.contains("goa", ignoreCase = true))
        assertTrue(answer.headline.contains("singapore", ignoreCase = true))
        assertTrue(answer.detail.contains("Goa: 1"))
        assertTrue(answer.detail.contains("Singapore: 1"))
    }

    @Test
    fun emptyCapabilityResultsUseTypedExecutorsButVisualSearchRemainsNoResult() {
        assertTrue(shouldExecuteCapabilityWithoutMediaHits(QueryIntent.COUNT, false))
        assertTrue(shouldExecuteCapabilityWithoutMediaHits(QueryIntent.SUM, false))
        assertTrue(shouldExecuteCapabilityWithoutMediaHits(QueryIntent.DOCUMENT_QA, false))
        assertFalse(shouldExecuteCapabilityWithoutMediaHits(QueryIntent.FIND_MEDIA, false))
        assertFalse(shouldExecuteCapabilityWithoutMediaHits(QueryIntent.COUNT, true))

        val emptySum = CapabilityAnswerExecutor.execute(
            context(QueryIntent.SUM).copy(hits = emptyList(), deterministicHits = emptyList(), matchCount = 0),
        )
        assertEquals("No compatible numeric facts", emptySum.headline)
    }

    @Test
    fun metadataCountIsDeterministicOnlyWithoutSemanticPredicates() {
        val countPlan = context(QueryIntent.COUNT).plan

        assertTrue(isDeterministicMetadataCount(countPlan, emptyList(), emptyList(), false))
        assertFalse(isDeterministicMetadataCount(countPlan, listOf("dog"), emptyList(), false))
        assertFalse(isDeterministicMetadataCount(countPlan, emptyList(), listOf("dog"), false))
        assertFalse(isDeterministicMetadataCount(countPlan, emptyList(), emptyList(), true))
    }

    @Test
    fun everyNonCompleteCountUsesRetrievalPassWording() {
        val bounded = context(QueryIntent.COUNT).copy(
            exactness = ResultExactness.ESTIMATED_FROM_RETRIEVAL,
            channelReports = emptyList(),
        )
        val answer = CapabilityAnswerExecutor.execute(bounded)

        assertTrue(answer.headline.contains("current retrieval pass", ignoreCase = true))
    }

    @Test
    fun listPersonUsesReviewedLabelsOnly() {
        val base = context(QueryIntent.LIST)
        val answer = CapabilityAnswerExecutor.execute(
            base.copy(
                plan = base.plan.copy(grouping = Grouping.PERSON),
                peopleByMedia = mapOf(
                    "one" to listOf(
                        IndexedPersonMetadata("person_dad", "Dad", "father", emptyList(), true, false, 2),
                    ),
                ),
            ),
        )

        assertTrue(answer.detail.contains("Dad"))
    }

    private fun context(intent: QueryIntent): CapabilityAnswerContext {
        val hits = listOf(
            hit("one", "Trip A", "INR 10.00", 1_700_000_000_000),
            hit("two", "Trip B", "INR 20.00", 1_710_000_000_000),
        )
        val aggregation = when (intent) {
            QueryIntent.COUNT -> AggregationSpec(AggregationOperation.COUNT)
            QueryIntent.SUM -> AggregationSpec(AggregationOperation.SUM, "total")
            QueryIntent.MIN_MAX -> AggregationSpec(AggregationOperation.MIN_MAX, "total")
            else -> null
        }
        val plan = GalleryQueryPlan(
            originalQuery = "fixture",
            intent = intent,
            grouping = when (intent) {
                QueryIntent.LIST -> Grouping.PLACE
                QueryIntent.COMPARE -> Grouping.EVENT
                else -> Grouping.NONE
            },
            aggregation = aggregation,
            ocrClause = if (intent in setOf(QueryIntent.ANSWER_FACT, QueryIntent.DOCUMENT_QA)) OcrClause(requestedField = "total") else null,
        )
        val events = mapOf(
            "one" to event(1, "Trip A"),
            "two" to event(2, "Trip B"),
        )
        return CapabilityAnswerContext(
            plan,
            hits,
            hits.size,
            ResultExactness.EXACT,
            hits.size,
            hits.size,
            emptyList(),
            emptyList(),
            eventsByMedia = events,
            sensitiveContentAuthorized = intent in setOf(QueryIntent.SUM, QueryIntent.MIN_MAX),
        )
    }

    private fun hit(id: String, album: String, total: String, capturedAt: Long): SearchHit {
        val item = GalleryItem(
            id = id,
            filename = "$id.jpg",
            title = album,
            creator = null,
            location = album,
            album = album,
            latitude = null,
            longitude = null,
            tags = emptyList(),
            description = album,
            license = "fixture",
            sourceUrl = "fixture",
            assetPath = null,
            capturedAt = capturedAt,
        )
        return SearchHit(
            item,
            1.0,
            listOf(EvidenceRecord("$id:total", id, "document_total", total, .95f)),
        )
    }

    private fun event(id: Long, title: String) = EventRecord(
        id,
        1_700_000_000_000,
        1_710_000_000_000,
        title,
        title,
        null,
        null,
        "TRIP",
        1,
        .9f,
        title.lowercase(),
        null,
        EventCompiler.PRODUCER_VERSION,
        false,
    )
}
