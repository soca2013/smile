package com.smile.sharding.page;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.reflection.factory.DefaultObjectFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.DefaultObjectWrapperFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.scripting.xmltags.DynamicContext;
import org.apache.ibatis.scripting.xmltags.DynamicSqlSource;
import org.apache.ibatis.scripting.xmltags.MixedSqlNode;
import org.apache.ibatis.scripting.xmltags.SqlNode;
import org.apache.ibatis.session.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class SqlUtil {

    private final static Log logger = LogFactory.getLog(SqlUtil.class);

    private static final List<ResultMapping> EMPTY_RESULTMAPPING = new ArrayList<ResultMapping>(0);

    //分页的id后缀
    private static final String SUFFIX_PAGE = "_Page";

    //count查询的id后缀
    private static final String SUFFIX_COUNT = "_Count";

    //第一个分页参数
    private static final String PAGEPARAMETER_FIRST = "First" + SUFFIX_PAGE;

    private static final String ORIGINAL_PARAMETER_OBJECT = "_ORIGINAL_PARAMETER_OBJECT";

    //第二个分页参数
    private static final String PAGEPARAMETER_SECOND = "Second" + SUFFIX_PAGE;

    private static final ObjectFactory DEFAULT_OBJECT_FACTORY = new DefaultObjectFactory();

    private static final ObjectWrapperFactory DEFAULT_OBJECT_WRAPPER_FACTORY = new DefaultObjectWrapperFactory();

    private static final ReflectorFactory REFLECTOR_FACTORY = new DefaultReflectorFactory();

    /**
     * 反射对象，增加对低版本Mybatis的支持
     *
     * @param object 反射对象
     * @return
     */
    public static MetaObject forObject(Object object) {
        return MetaObject.forObject(object, DEFAULT_OBJECT_FACTORY, DEFAULT_OBJECT_WRAPPER_FACTORY, REFLECTOR_FACTORY);
    }

    private SqlUtil.Parser sqlParser;

    //数据库方言 - 使用枚举限制数据库类型
    public enum Dialect {
        mysql, oracle, hsqldb, postgresql
    }

    /**
     * 构造方法
     *
     * @param strDialect
     */
    public SqlUtil(String strDialect) {
        if (strDialect == null || "".equals(strDialect)) {
            throw new IllegalArgumentException("Mybatis分页插件无法获取dialect参数!");
        }
        try {
            Dialect dialect = Dialect.valueOf(strDialect);
//            String sqlParserClass = this.getClass().getPackage().getName() + ".SqlParser";
//            try {
//                //使用SqlParser必须引入jsqlparser-x.x.x.jar
//                Class.forName("net.sf.jsqlparser.statement.select.Select");
//                sqlParser = (Parser) Class.forName(sqlParserClass).getConstructor(Dialect.class).newInstance(dialect);
//            } catch (Exception e) {
//                logger.error("cann't find sql Parser", e);
//                //找不到时，不用处理
//            }
//            if (sqlParser == null) {
//                sqlParser = SimpleParser.newParser(dialect);
//            }

            sqlParser = SimpleParser.newParser(dialect);
        } catch (IllegalArgumentException e) {
            String dialects = null;
            for (Dialect d : Dialect.values()) {
                if (dialects == null) {
                    dialects = d.toString();
                } else {
                    dialects += "," + d;
                }
            }
            throw new IllegalArgumentException("Mybatis分页插件dialect参数值错误，可选值为[" + dialects + "]");
        }
    }

    /**
     * 设置分页参数
     *
     * @param parameterObject
     * @param boundSql
     * @param pagination
     * @return
     */
    public Map setPageParameter(MappedStatement ms, Object parameterObject, BoundSql boundSql, Pagination pagination) {
        return sqlParser.setPageParameter(ms, parameterObject, boundSql, pagination);
    }

    /**
     * 获取count查询的MappedStatement
     *
     * @param ms
     * @param boundSql
     * @return
     */
    public MappedStatement getCountMappedStatement(MappedStatement ms, BoundSql boundSql, Pagination pagination) {
        return getMappedStatement(ms, boundSql, SUFFIX_COUNT, pagination);
    }

    /**
     * 获取分页查询的MappedStatement
     *
     * @param ms
     * @param boundSql
     * @return
     */
    public MappedStatement getPageMappedStatement(MappedStatement ms, BoundSql boundSql, Pagination pagination) {
        return getMappedStatement(ms, boundSql, SUFFIX_PAGE, pagination);
    }

    /**
     * 处理SQL
     */
    public interface Parser {
        void isSupportedSql(String sql);

        String getCountSql(String sql);

        String getPageSql(String sql, Pagination pagination);

        Map setPageParameter(MappedStatement ms, Object parameterObject, BoundSql boundSql, Pagination pagination);
    }

    public static abstract class SimpleParser implements Parser {
        public static Parser newParser(Dialect dialect) {
            Parser parser = null;
            switch (dialect) {
                case mysql:
                    parser = new MysqlParser();
                    break;
                case oracle:
                    parser = new OracleParser();
                    break;
                case hsqldb:
                    parser = new HsqldbParser();
                    break;
                case postgresql:
                default:
                    parser = new PostgreSQLParser();
            }
            return parser;
        }

        public void isSupportedSql(String sql) {
            if (sql.trim().toUpperCase().endsWith("FOR UPDATE")) {
                throw new RuntimeException("分页插件不支持包含for update的sql");
            }
        }

        /**
         * 获取总数sql - 如果要支持其他数据库，修改这里就可以
         *
         * @param sql 原查询sql
         * @return 返回count查询sql
         */
        public String getCountSql(final String sql) {
            isSupportedSql(sql);
            StringBuilder stringBuilder = new StringBuilder(sql.length() + 40);
            stringBuilder.append("select count(*) from (");
            stringBuilder.append(sql);
            stringBuilder.append(") tmp_count");
            return stringBuilder.toString();
        }

        /**
         * 获取分页sql - 如果要支持其他数据库，修改这里就可以
         *
         * @param sql 原查询sql
         * @return 返回分页sql
         */
        public abstract String getPageSql(String sql, Pagination pagination);

        public Map<String, Object> setPageParameter(MappedStatement ms, Object parameterObject, BoundSql boundSql, Pagination pagination) {
            Map paramMap = null;
            if (parameterObject == null) {
                paramMap = new HashMap();
            } else if (parameterObject instanceof Map) {
                //解决不可变Map的情况
                paramMap = new HashMap();
                paramMap.putAll((Map) parameterObject);
            } else {
                paramMap = new HashMap();
                //动态sql时的判断条件不会出现在ParameterMapping中，但是必须有，所以这里需要收集所有的getter属性
                //TypeHandlerRegistry可以直接处理的会作为一个直接使用的对象进行处理
                boolean hasTypeHandler = ms.getConfiguration().getTypeHandlerRegistry().hasTypeHandler(parameterObject.getClass());
                MetaObject metaObject = SystemMetaObject.forObject(parameterObject);
                if (!hasTypeHandler) {
                    for (String name : metaObject.getGetterNames()) {
                        paramMap.put(name, metaObject.getValue(name));
                    }
                }
                //下面这段方法，主要解决一个常见类型的参数时的问题
                if (boundSql.getParameterMappings() != null && boundSql.getParameterMappings().size() > 0) {
                    for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
                        String name = parameterMapping.getProperty();
                        if (!name.equals(PAGEPARAMETER_FIRST)
                                && !name.equals(PAGEPARAMETER_SECOND)
                                && paramMap.get(name) == null) {
                            if (hasTypeHandler
                                    || parameterMapping.getJavaType().equals(parameterObject.getClass())) {
                                paramMap.put(name, parameterObject);
                                break;
                            }
                        }
                    }
                }
            }
            //备份原始参数对象
            paramMap.put(ORIGINAL_PARAMETER_OBJECT, parameterObject);
            return paramMap;
        }

    }


    //Mysql
    private static class MysqlParser extends SimpleParser {
        @Override
        public String getPageSql(String sql, Pagination pagination) {
            StringBuilder sqlBuilder = new StringBuilder(sql.length() + 40);
            sqlBuilder.append("select * from (");
            sqlBuilder.append(sql);
            sqlBuilder.append("");
//            if (pagination != null && pagination.isAutoOrder() && StringUtils.isNotEmpty(pagination.getSort())) {
//                sqlBuilder.append(" order by " + pagination.getSort() + " " + pagination.getSord());
//            }
            sqlBuilder.append(") as tmp_page  limit ? offset ? ");
            return sqlBuilder.toString();
        }

        @Override
        public Map setPageParameter(MappedStatement ms, Object parameterObject, BoundSql boundSql, Pagination pagination) {
            Map paramMap = super.setPageParameter(ms, parameterObject, boundSql, pagination);
            paramMap.put(PAGEPARAMETER_FIRST, pagination.getLimit());
            paramMap.put(PAGEPARAMETER_SECOND, pagination.getOffset());
            return paramMap;
        }
    }

    //Oracle
    private static class OracleParser extends SimpleParser {
        @Override
        public String getPageSql(String sql, Pagination pagination) {
            StringBuilder sqlBuilder = new StringBuilder(sql.length() + 120);
            sqlBuilder.append("select * from ( select tmp_page.*, rownum row_id from ( ");
            sqlBuilder.append(sql);
//            if (pagination != null && pagination.isAutoOrder() && StringUtils.isNotEmpty(pagination.getSort())) {
//                sqlBuilder.append(" order by " + pagination.getSort() + " " + pagination.getSord());
//            }
            sqlBuilder.append(" ) tmp_page where rownum <= ? ) where row_id > ?");
            return sqlBuilder.toString();
        }

        @Override
        public Map setPageParameter(MappedStatement ms, Object parameterObject, BoundSql boundSql, Pagination pagination) {
            Map paramMap = super.setPageParameter(ms, parameterObject, boundSql, pagination);
            paramMap.put(PAGEPARAMETER_FIRST, pagination.getLimit()+pagination.getOffset());
            paramMap.put(PAGEPARAMETER_SECOND, pagination.getOffset());
            return paramMap;
        }
    }

    //Oracle
    private static class HsqldbParser extends SimpleParser {
        @Override
        public String getPageSql(String sql, Pagination pagination) {
            StringBuilder sqlBuilder = new StringBuilder(sql.length() + 20);
            sqlBuilder.append(sql);
            sqlBuilder.append(" limit ? offset ?");
            return sqlBuilder.toString();
        }

        @Override
        public Map setPageParameter(MappedStatement mappedStatement, Object parameterObject, BoundSql boundSql, Pagination pagination) {
            Map paramMap = super.setPageParameter(mappedStatement, parameterObject, boundSql, pagination);
            paramMap.put(PAGEPARAMETER_FIRST, pagination.getLimit());
            paramMap.put(PAGEPARAMETER_SECOND, pagination.getOffset());
            return paramMap;
        }
    }

    //PostgreSQL
    private static class PostgreSQLParser extends SimpleParser {
        @Override
        public String getPageSql(String sql, Pagination pagination) {
            StringBuilder sqlBuilder = new StringBuilder(sql.length() + 50);
            sqlBuilder.append("select * from (");
            sqlBuilder.append(sql);
            sqlBuilder.append(") as tmp_page limit ? offset ?");
            return sqlBuilder.toString();
        }

        @Override
        public Map setPageParameter(MappedStatement ms, Object parameterObject, BoundSql boundSql, Pagination pagination) {
            Map paramMap = super.setPageParameter(ms, parameterObject, boundSql, pagination);
            paramMap.put(PAGEPARAMETER_FIRST, pagination.getLimit());
            paramMap.put(PAGEPARAMETER_SECOND, pagination.getOffset());
            return paramMap;
        }
    }

    /**
     * 自定义简单SqlSource
     */
    private class BoundSqlSqlSource implements SqlSource {
        BoundSql boundSql;

        Pagination pagination;

        public BoundSqlSqlSource(BoundSql boundSql, Pagination pagination) {
            this.boundSql = boundSql;
            this.pagination = pagination;
        }

        public BoundSql getBoundSql(Object parameterObject) {
            return boundSql;
        }

        public BoundSql getBoundSql() {
            return boundSql;
        }

        public Pagination getPagination() {
            return pagination;
        }

        public void setPagination(Pagination pagination) {
            this.pagination = pagination;
        }
    }

    /**
     * 自定义动态SqlSource
     */
    private class MyDynamicSqlSource implements SqlSource {
        private Configuration configuration;

        private SqlNode rootSqlNode;

        private Pagination pagination;

        /**
         * 用于区分动态的count查询或分页查询
         */
        private Boolean count;

        public MyDynamicSqlSource(Configuration configuration, SqlNode rootSqlNode, Boolean count, Pagination pagination) {
            this.configuration = configuration;
            this.rootSqlNode = rootSqlNode;
            this.count = count;
            this.pagination = pagination;
        }

        public BoundSql getBoundSql(Object parameterObject) {
//            if (parameterObject != null
//                    && parameterObject instanceof Map
//                    && ((Map) parameterObject).containsKey(ORIGINAL_PARAMETER_OBJECT)) {
//                parameterObject = ((Map) parameterObject).get(ORIGINAL_PARAMETER_OBJECT);
//            }
            DynamicContext context = new DynamicContext(configuration, parameterObject);
            rootSqlNode.apply(context);
            SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
            Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
            SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings());
            BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
            //设置条件参数
            for (Map.Entry<String, Object> entry : context.getBindings().entrySet()) {
                boundSql.setAdditionalParameter(entry.getKey(), entry.getValue());
            }
            BoundSqlSqlSource boundSqlSqlSource = new BoundSqlSqlSource(boundSql, pagination);
            if (count) {
                boundSqlSqlSource = getCountSqlSource(boundSqlSqlSource);
            } else {
                boundSqlSqlSource = getPageSqlSource(configuration, boundSqlSqlSource, pagination);
            }
            return boundSqlSqlSource.getBoundSql();
        }

        public Pagination getPagination() {
            return pagination;
        }

        public void setPagination(Pagination pagination) {
            this.pagination = pagination;
        }
    }


    /**
     * 获取ms - 在这里对新建的ms做了缓存，第一次新增，后面都会使用缓存值
     *
     * @param ms
     * @param boundSql
     * @param suffix
     * @return
     */
    private MappedStatement getMappedStatement(MappedStatement ms, BoundSql boundSql, String suffix, Pagination pagination) {
        MappedStatement qs = null;
        try {
            qs = ms.getConfiguration().getMappedStatement(ms.getId() + suffix);
            if (qs.getSqlSource() instanceof BoundSqlSqlSource) {
                ((BoundSqlSqlSource) qs.getSqlSource()).setPagination(pagination);
            }
            if (qs.getSqlSource() instanceof MyDynamicSqlSource) {
                ((MyDynamicSqlSource) qs.getSqlSource()).setPagination(pagination);
            }
        } catch (Exception e) {
//            logger.warn("get MappedStatement error", e); //ignore
        }
        if (qs == null) {
            //创建一个新的MappedStatement
            qs = newMappedStatement(ms, getNewSqlSource(ms, new BoundSqlSqlSource(boundSql, pagination), suffix, pagination), suffix);
            try {
                ms.getConfiguration().addMappedStatement(qs);
            } catch (Exception e) {
                logger.warn("get new MappedStatement error", e);
                //ignore
            }
        }
        return qs;
    }

    /**
     * 新建count查询和分页查询的MappedStatement
     *
     * @param ms
     * @param newSqlSource
     * @param suffix
     * @return
     */
    private MappedStatement newMappedStatement(MappedStatement ms, SqlSource newSqlSource, String suffix) {
        String id = ms.getId() + suffix;
        MappedStatement.Builder builder = new MappedStatement.Builder(ms.getConfiguration(), id, newSqlSource, ms.getSqlCommandType());
        builder.resource(ms.getResource());
        builder.fetchSize(ms.getFetchSize());
        builder.statementType(ms.getStatementType());
        builder.keyGenerator(ms.getKeyGenerator());

        if (ms.getKeyProperties() != null && ms.getKeyProperties().length != 0) {
            StringBuilder keyProperties = new StringBuilder();
            for (String keyProperty : ms.getKeyProperties()) {
                keyProperties.append(keyProperty).append(",");
            }
            keyProperties.delete(keyProperties.length() - 1, keyProperties.length());
            builder.keyProperty(keyProperties.toString());
        }
        builder.timeout(ms.getTimeout());
        builder.parameterMap(ms.getParameterMap());
        if (suffix == SUFFIX_PAGE) {
            builder.resultMaps(ms.getResultMaps());
        } else {
            //count查询返回值int
            List<ResultMap> resultMaps = new ArrayList<ResultMap>();
            ResultMap resultMap = new ResultMap.Builder(ms.getConfiguration(), id, int.class, EMPTY_RESULTMAPPING).build();
            resultMaps.add(resultMap);
            builder.resultMaps(resultMaps);
        }
        builder.resultSetType(ms.getResultSetType());
        builder.cache(ms.getCache());
        builder.flushCacheRequired(ms.isFlushCacheRequired());
        builder.useCache(ms.isUseCache());
//        builder.useCache(false);
        return builder.build();
    }

    /**
     * 判断当前执行的是否为动态sql
     *
     * @param ms
     * @return
     */
    public boolean isDynamic(MappedStatement ms) {
        return ms.getSqlSource() instanceof DynamicSqlSource;
    }

    /**
     * 获取新的sqlSource
     *
     * @param ms
     * @param newSqlSource
     * @param suffix
     * @return
     */
    private SqlSource getNewSqlSource(MappedStatement ms, BoundSqlSqlSource newSqlSource, String suffix, Pagination pagination) {
        //从XMLLanguageDriver.java和XMLScriptBuilder.java可以看出只有两种SqlSource
        //如果是动态sql
        if (isDynamic(ms)) {
            MetaObject msObject = forObject(ms);
            SqlNode sqlNode = (SqlNode) msObject.getValue("sqlSource.rootSqlNode");
            MixedSqlNode mixedSqlNode = null;
            if (sqlNode instanceof MixedSqlNode) {
                mixedSqlNode = (MixedSqlNode) sqlNode;
            } else {
                List<SqlNode> contents = new ArrayList<SqlNode>(1);
                contents.add(sqlNode);
                mixedSqlNode = new MixedSqlNode(contents);
            }
            return new MyDynamicSqlSource(ms.getConfiguration(), mixedSqlNode, suffix == SUFFIX_COUNT, pagination);
        }
        //如果是静态分页sql
        else if (suffix == SUFFIX_PAGE) {
            //改为分页sql
            return getPageSqlSource(ms.getConfiguration(), newSqlSource, pagination);
        }
        //如果是静态count-sql
        else {
            return getCountSqlSource(newSqlSource);
        }
    }

    /**
     * 获取分页的sqlSource
     *
     * @param configuration
     * @param newSqlSource
     * @return
     */
    private BoundSqlSqlSource getPageSqlSource(Configuration configuration, BoundSqlSqlSource newSqlSource, Pagination pagination) {
        String sql = newSqlSource.getBoundSql().getSql();
        //改为分页sql
        MetaObject sqlObject = forObject(newSqlSource);
        sqlObject.setValue("boundSql.sql", sqlParser.getPageSql(sql, pagination));
        //添加参数映射
        List<ParameterMapping> newParameterMappings = new ArrayList<ParameterMapping>();
        newParameterMappings.addAll(newSqlSource.getBoundSql().getParameterMappings());
        newParameterMappings.add(new ParameterMapping.Builder(configuration, PAGEPARAMETER_FIRST, Integer.class).build());
        newParameterMappings.add(new ParameterMapping.Builder(configuration, PAGEPARAMETER_SECOND, Integer.class).build());
        sqlObject.setValue("boundSql.parameterMappings", newParameterMappings);
        return newSqlSource;
    }

    /**
     * 获取count的sqlSource
     *
     * @param newSqlSource
     * @return
     */
    private BoundSqlSqlSource getCountSqlSource(BoundSqlSqlSource newSqlSource) {
        String sql = newSqlSource.getBoundSql().getSql();
        MetaObject sqlObject = forObject(newSqlSource);
        sqlObject.setValue("boundSql.sql", sqlParser.getCountSql(sql));
        return newSqlSource;
    }

    /**
     * 测试[控制台输出]count和分页sql
     *
     * @param dialet      数据库类型
     * @param originalSql 原sql
     */
    public static void testSql(String dialet, String originalSql) {
        SqlUtil sqlUtil = new SqlUtil(dialet);
        String countSql = sqlUtil.sqlParser.getCountSql(originalSql);
        System.out.println(countSql);
        String pageSql = sqlUtil.sqlParser.getPageSql(originalSql, null);
        System.out.println(pageSql);
    }
}