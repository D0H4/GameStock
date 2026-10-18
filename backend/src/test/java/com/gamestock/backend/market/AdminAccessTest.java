package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamestock.backend.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminAccessTest {
    private AuthService.LoginUser user(String role){return new AuthService.LoginUser(7,"tester","test@example.com","",0,0,false,role);}
    @Test void ordinaryUsersCannotUseAnyOperatorScope(){
        var db=mock(JdbcTemplate.class);var auth=mock(AuthService.class);when(auth.requireUser("token")).thenReturn(user("USER"));
        when(db.queryForList(anyString(),eq(String.class),eq(7L))).thenReturn(List.of());var access=new AdminSupport(db,auth,new ObjectMapper());
        for(String permission:AdminSupport.PERMISSIONS)assertEquals(403,assertThrows(ResponseStatusException.class,()->access.require("token",permission)).getStatusCode().value());
    }
    @Test void contentOperatorsGetReadButCannotAdjustCashOrGrantPermissions(){
        var db=mock(JdbcTemplate.class);var auth=mock(AuthService.class);when(auth.requireUser("token")).thenReturn(user("USER"));
        when(db.queryForList(anyString(),eq(String.class),eq(7L))).thenReturn(List.of("CONTENT"));
        when(auth.requireAdmin("token")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));var access=new AdminSupport(db,auth,new ObjectMapper());
        assertEquals(7,access.require("token","READ"));assertEquals(7,access.require("token","CONTENT"));
        assertThrows(ResponseStatusException.class,()->access.require("token","REWARDS"));assertThrows(ResponseStatusException.class,()->access.root("token"));
    }
    @Test void revocationTakesEffectOnTheNextRequest(){
        var db=mock(JdbcTemplate.class);var auth=mock(AuthService.class);when(auth.requireUser("token")).thenReturn(user("USER"));
        when(db.queryForList(anyString(),eq(String.class),eq(7L))).thenReturn(List.of("MARKET"),List.of());var access=new AdminSupport(db,auth,new ObjectMapper());
        assertEquals(7,access.require("token","MARKET"));assertThrows(ResponseStatusException.class,()->access.require("token","MARKET"));
    }
    @Test void rootAdminsAreNotGrantedThroughTheDelegationTable(){
        var db=mock(JdbcTemplate.class);var auth=mock(AuthService.class);when(auth.requireUser("token")).thenReturn(user("ADMIN"));
        assertEquals(7,new AdminSupport(db,auth,new ObjectMapper()).require("token","SEASONS"));verifyNoInteractions(db);
    }
    @Test void realHttpMappingsDenyUnauthenticatedReadAndFinancialWrites() throws Exception {
        var access=mock(AdminSupport.class);var operations=mock(AdminOperationsService.class);
        when(access.require(isNull(),anyString())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));when(access.root(isNull())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new AdminOperationsController(access,operations,mock(AdminContentService.class),mock(AdminSeasonService.class),new MarketMetrics())).build();
        mvc.perform(get("/api/admin/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/users/1/cash-adjustments").contentType("application/json").content("{\"requestKey\":\"x\",\"amount\":100,\"reason\":\"test\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/seasons/1/reset").contentType("application/json").content("{\"reason\":\"test\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/dashboard").header("Authorization","allowed")).andExpect(status().isOk());
        verify(operations).dashboard();verify(operations,never()).cash(anyLong(),anyLong(),any());
    }
    @Test void administrativeHaltsRejectLimitOrdersInsideTheMatchingBook(){
        var stock=new BatchOrderBook.Stock(1,"UMA",10000,10000,10000,10000,false);stock.adminHalted=true;
        var book=new BatchOrderBook(new HashMap<>(Map.of(7L,new BatchOrderBook.Account(7,1000000,false))),Map.of(1L,stock),new HashMap<>(),List.of(),0,System.currentTimeMillis());
        assertNull(book.submit(7,1,"BUY","LIMIT",1,10000,System.currentTimeMillis(),1000));assertTrue(book.accepted.isEmpty());
    }
}
