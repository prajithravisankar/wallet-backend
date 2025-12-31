package com.example.plugins

import at.favre.lib.crypto.bcrypt.BCrypt
import com.example.plugins.UserRepository
import com.example.plugins.TransactionRepository
import com.example.plugins.BudgetRepository
import com.example.Database
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * Thread-based concurrent demo-data generator.
 *
 * Uses:
 *  - ReentrantLock                   → ensures insert user is sequential.
 *  - ReentrantReadWriteLock          → protects shared userId list.
 *  - Thread + join                   → explicit concurrency demonstration.
 *
 * Now fully rewritten to use Repository architecture (Option B).
 */
object DemoDataSeeder {

    // -------------------------------------------------------------------------
    // Configuration constants
    // -------------------------------------------------------------------------
    private const val NUMBER_OF_DEMO_USERS = 10
    private const val NUMBER_OF_TRANSACTIONS_PER_CATEGORY_AND_USER = 5

    private val categories = listOf(
        "Food",
        "Utilities",
        "Transportation",
        "Personal",
        "Family",
        "Entertainment",
        "Subscriptions",
        "Miscellaneous"
    )

    // -------------------------------------------------------------------------
    // Repositories
    // -------------------------------------------------------------------------
    private val userRepo = UserRepository()
    private val txRepo = TransactionRepository()
    private val budgetRepo = BudgetRepository()

    // -------------------------------------------------------------------------
    // Concurrency primitives + shared data structures
    // -------------------------------------------------------------------------

    private val userInsertLock = ReentrantLock() // Ensures that only one thread at a time can run a critical section, protects the user creation block, so user IDs are generated in a safe, predictable way.
    private val userIdListRWLock = ReentrantReadWriteLock() // Many threads can read at the same time.Only one thread can write, and nobody can read while writing.
    private val sharedUserIdList = mutableListOf<Int>() // This is the shared data that could be accessed by multiple threads.That’s why we wrap access with read/write locks.

    // -------------------------------------------------------------------------
    // ENTRY POINT
    // -------------------------------------------------------------------------

    fun reseedDemoDataOnApplicationStartup() {
        println("========== [DemoDataSeeder] BEGIN DEMO DATA RESEED PIPELINE ==========")

        deleteAllTables()

        createDemoUsersSequentially()

        val userIdsSnapshot = safelyGetAllUserIdsSnapshot()

        createConcurrentWorkerThreadsForAllUsers(userIdsSnapshot)

        println("========== [DemoDataSeeder] FINISHED DEMO DATA RESEED PIPELINE ==========")
    }

    // -------------------------------------------------------------------------
    // 0) DATABASE WIPE
    // -------------------------------------------------------------------------

    private fun deleteAllTables() {
        Database.connect().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    TRUNCATE TABLE transactions, budgets, users 
                    RESTART IDENTITY CASCADE;
                    """.trimIndent()
                )
            }
        }
        println("[DemoDataSeeder] All tables truncated.")
    }

    // -------------------------------------------------------------------------
    // 1) CREATE USERS (SEQUENTIAL + LOCKED)
    // -------------------------------------------------------------------------

    private fun createDemoUsersSequentially() {
        // Ensures the entire loop is executed by only one thread at a time.
        userInsertLock.withLock {

            println("[DemoDataSeeder] Creating demo users...")

            for (i in 1..NUMBER_OF_DEMO_USERS) {
                val firstName = "User$i"
                val lastName = "Demo"
                val email = "user$i@example.com"
                val rawPassword = "password$i"

                val hashedPassword = BCrypt
                    .withDefaults()
                    .hashToString(12, rawPassword.toCharArray())

                val newUserId = userRepo.createUser(
                    firstName,
                    lastName,
                    email,
                    hashedPassword
                )

                addUserIdThreadSafely(newUserId)

                println("[DemoDataSeeder] Created demo user id=$newUserId")
            }
        }
    }

    private fun addUserIdThreadSafely(newId: Int) {
        userIdListRWLock.writeLock().withLock {
            sharedUserIdList.add(newId)
        }
    }

    private fun safelyGetAllUserIdsSnapshot(): List<Int> {
        userIdListRWLock.readLock().withLock {
            return sharedUserIdList.toList()
        }
    }

    // -------------------------------------------------------------------------
    // 2) START CONCURRENT GENERATORS FOR USERS
    // -------------------------------------------------------------------------

    private fun createConcurrentWorkerThreadsForAllUsers(userIds: List<Int>) {
        val allThreads = mutableListOf<Thread>()

        for (userId in userIds) {

            // One transaction thread per category
            for (category in categories) {
                val t = createAndStartTransactionThread(userId, category)
                allThreads.add(t)
            }

            // One budget thread per user
            val b = createAndStartBudgetThread(userId)
            allThreads.add(b)
        }

        waitForAllWorkersToFinish(allThreads)
    }

    private fun createAndStartTransactionThread(userId: Int, category: String): Thread {
        val thread = Thread({
            generateTransactions(userId, category)
        }, "Tx-User$userId-$category")

        thread.start()
        return thread
    }

    private fun createAndStartBudgetThread(userId: Int): Thread {
        val thread = Thread({
            generateBudgets(userId)
        }, "Budget-User$userId")

        thread.start()
        return thread
    }

    private fun waitForAllWorkersToFinish(threads: List<Thread>) {
        threads.forEach { t ->
            try {
                t.join()
            } catch (e: InterruptedException) {
                println("[DemoDataSeeder] Worker ${t.name} interrupted: ${e.message}")
                Thread.currentThread().interrupt()
            }
        }

        println("[DemoDataSeeder] All worker threads completed.")
    }

    // -------------------------------------------------------------------------
    // 3) GENERATE TRANSACTIONS (THREAD BODY)
    // -------------------------------------------------------------------------

    private fun generateTransactions(userId: Int, category: String) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        repeat(NUMBER_OF_TRANSACTIONS_PER_CATEGORY_AND_USER) { index ->
            val num = index + 1

            val isExpense = (num % 3 != 0)
            val txType = if (isExpense) "expense" else "income"

            val amount = if (isExpense)
                BigDecimal((10..100).random())
            else
                BigDecimal((100..500).random())

            val daysAgo = (0..60).random().toLong()
            val dateIso = now.minusDays(daysAgo).toString()

            txRepo.insertTransaction(
                userId = userId,
                title = "$category transaction $num",
                category = category,
                subCategory = "General",
                txType = txType,
                amount = amount,
                isoDate = dateIso,
                description = "Sample $category transaction for user $userId",
                location = "Demo Location"
            )
        }

        println("[DemoDataSeeder] Completed transactions for user=$userId category=$category")
    }

    // -------------------------------------------------------------------------
    // 4) GENERATE BUDGETS (THREAD BODY)
    // -------------------------------------------------------------------------

    private fun generateBudgets(userId: Int) {
        val today = LocalDate.now()

        val weekStart = today.with(DayOfWeek.MONDAY)
        val weekEnd = weekStart.plusDays(6)

        val monthStart = today.withDayOfMonth(1)
        val monthEnd = monthStart.plusMonths(1).minusDays(1)

        val yearStart = today.withDayOfYear(1)
        val yearEnd = yearStart.plusYears(1).minusDays(1)

        for (category in categories) {

            budgetRepo.insertBudget(
                userId, category, "General",
                BigDecimal("50.00"),
                "daily",
                today, today,
                "Daily $category budget"
            )

            budgetRepo.insertBudget(
                userId, category, "General",
                BigDecimal("200.00"),
                "weekly",
                weekStart, weekEnd,
                "Weekly $category budget"
            )

            budgetRepo.insertBudget(
                userId, category, "General",
                BigDecimal("800.00"),
                "monthly",
                monthStart, monthEnd,
                "Monthly $category budget"
            )

            budgetRepo.insertBudget(
                userId, category, "General",
                BigDecimal("9600.00"),
                "yearly",
                yearStart, yearEnd,
                "Yearly $category budget"
            )
        }

        println("[DemoDataSeeder] Completed budgets for user=$userId")
    }
}
