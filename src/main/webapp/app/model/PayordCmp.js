/**
 * Created by lev on 13.03.2017.
 */
Ext.define('BillWebApp.model.PayordCmp', {
    extend: 'Ext.data.Model',
    identifier: {
        type: 'sequential',
        id: 'foo'
    },
    idProperty: 'id',
    fields: [
        { name: 'id', mapping: 'id', type: 'int'},
        { name: 'dtf', dateFormat: 'Y-m-d H:i:s', type: 'date', persist: false},
        { name: 'username', type: 'string', persist: false},
        { name: 'payordFk', type: 'int'},
        { name: 'varFk', type: 'int'},
        { name: 'servFk', type: 'int'},
        { name: 'orgFk', type: 'int'},
        { name: 'areaFk', type: 'int'},
        { name: 'koFk', type: 'int'},
        { name: 'koName', type: 'string', persist: false }, // Имя объекта (только для отображения)
        { name: 'koExtFk', type: 'int'},
        { name: 'koExtName', type: 'string', persist: false }, // Имя доп. объекта (только для отображения)
        { name: 'mark', type: 'string', defaultValue: 'A' },
        { name: 'summa', type: 'float', persist: false },
        { name: 'selDays', type: 'string' },
        { name: 'periodTpFk', type: 'int', convert: null}
    ]
});